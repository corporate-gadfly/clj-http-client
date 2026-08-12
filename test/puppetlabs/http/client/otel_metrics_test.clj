(ns puppetlabs.http.client.otel-metrics-test
  (:require [clojure.test :refer :all]
            [puppetlabs.http.client.async :as async]
            [puppetlabs.http.client.common :as common]
            [puppetlabs.http.client.metrics :as metrics]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services.webserver.jetty-service :as jetty]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as testlogging]
            [schema.test :as schema-test])
  (:import (com.puppetlabs.trapperkeeper.metrics PullMetricReader)
           (io.opentelemetry.api.common AttributeKey)
           (io.opentelemetry.sdk.metrics SdkMeterProvider)
           (io.opentelemetry.sdk.metrics.data HistogramPointData)))

(use-fixtures :once schema-test/validate-schemas)

(tk/defservice otel-test-web-service
  [[:WebserverService add-ring-handler]]
  (init [this context]
    (add-ring-handler
     (fn [req]
       (case (:uri req)
         "/hello" {:status 200 :body "Hello!"}
         "/slow"  (do (Thread/sleep 10) {:status 200 :body "slow"})
         {:status 404 :body "not found"}))
     "/")
    context))

(defn make-provider-and-reader []
  (let [reader   (PullMetricReader.)
        provider (-> (SdkMeterProvider/builder)
                     (.registerMetricReader reader)
                     (.build))]
    {:provider provider :reader reader}))

(defn shutdown! [{:keys [provider]}]
  (.shutdown ^SdkMeterProvider provider))

(defn collected-metrics [{:keys [reader]}]
  (seq (.collectAllMetrics ^PullMetricReader reader)))

(defn find-metric [metrics name]
  (first (filter #(= name (.getName %)) metrics)))

(defn get-point-attr [point attr-key]
  (.get (.getAttributes point) (AttributeKey/stringKey attr-key)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Unit tests — create-otel-client-histogram

(deftest create-otel-client-histogram-test
  (testing "creates a histogram from a live MeterProvider"
    (let [ctx (make-provider-and-reader)]
      (try
        (let [histo (metrics/create-otel-client-histogram (:provider ctx) "test.scope")]
          (is (some? histo))
          (.record histo 42.0)
          (let [md (find-metric (collected-metrics ctx) "http.client.request.duration")]
            (is (some? md))
            (is (= "ms" (.getUnit md)))))
        (finally (shutdown! ctx)))))

  (testing "returns nil when meter-provider is nil"
    (is (nil? (metrics/create-otel-client-histogram nil "test.scope")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Unit tests — record-otel-client-duration!

(deftest record-otel-client-duration-test
  (testing "records duration with correct attributes"
    (let [ctx (make-provider-and-reader)]
      (try
        (let [histo (metrics/create-otel-client-histogram (:provider ctx) "test.scope")]
          (metrics/record-otel-client-duration! histo "http://example.com/api" :get 200 15.0)
          (let [md    (find-metric (collected-metrics ctx) "http.client.request.duration")
                point (first (.getPoints (.getData md)))]
            (is (= "example.com" (get-point-attr point "server.address")))
            (is (= "GET" (get-point-attr point "http.request.method")))
            (is (= "200" (get-point-attr point "http.response.status_code")))))
        (finally (shutdown! ctx)))))

  (testing "no-op when histogram is nil"
    (is (nil? (metrics/record-otel-client-duration! nil "http://x" :get 200 1.0)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Integration tests — sync client with OTEL histogram

(deftest sync-client-otel-metrics-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config
     app
     [jetty/jetty-service otel-test-web-service]
     {:webserver {:port 10001}}

     (let [ctx (make-provider-and-reader)]
       (try
         (let [histo  (metrics/create-otel-client-histogram (:provider ctx) "test.http.client")
               client (sync/create-client {:otel-histogram histo})]
           (try
             (testing "sync GET records OTEL histogram"
               (let [resp (common/get client "http://localhost:10001/hello")]
                 (is (= 200 (:status resp)))))

             (testing "sync POST records OTEL histogram"
               (let [resp (common/post client "http://localhost:10001/hello")]
                 (is (= 200 (:status resp)))))

             (testing "histogram has data points with correct attributes"
               (let [md     (find-metric (collected-metrics ctx) "http.client.request.duration")
                     points (.getPoints (.getData md))]
                 (is (= 2 (count points)))  ;; GET and POST are distinct attribute combos
                 (let [get-point (first (filter #(= "GET" (get-point-attr % "http.request.method")) points))
                       post-point (first (filter #(= "POST" (get-point-attr % "http.request.method")) points))]
                   (is (some? get-point))
                   (is (some? post-point))
                   (is (= "localhost" (get-point-attr get-point "server.address")))
                   (is (= "200" (get-point-attr get-point "http.response.status_code")))
                   (is (= 1 (.getCount ^HistogramPointData get-point)))
                   (is (= 1 (.getCount ^HistogramPointData post-point))))))

             (finally (common/close client))))
         (finally (shutdown! ctx)))))))

(deftest sync-client-without-otel-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config
     app
     [jetty/jetty-service otel-test-web-service]
     {:webserver {:port 10001}}

     (testing "sync client works fine without :otel-histogram"
       (let [client (sync/create-client {})]
         (try
           (let [resp (common/get client "http://localhost:10001/hello")]
             (is (= 200 (:status resp))))
           (finally (common/close client))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Integration tests — async client with OTEL histogram

(deftest async-client-otel-metrics-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config
     app
     [jetty/jetty-service otel-test-web-service]
     {:webserver {:port 10001}}

     (let [ctx (make-provider-and-reader)]
       (try
         (let [histo  (metrics/create-otel-client-histogram (:provider ctx) "test.http.client")
               client (async/create-client {:otel-histogram histo})]
           (try
             (testing "async GET records OTEL histogram"
               (let [resp @(common/get client "http://localhost:10001/hello")]
                 (is (= 200 (:status resp)))))

             (testing "async request to /slow records duration"
               (let [resp @(common/get client "http://localhost:10001/slow")]
                 (is (= 200 (:status resp)))))

             (testing "histogram accumulated 2 requests"
               (let [md     (find-metric (collected-metrics ctx) "http.client.request.duration")
                     points (.getPoints (.getData md))
                     point  (first points)]
                 ;; Both GETs to same server+method+status → same data point
                 (is (= 1 (count points)))
                 (is (= 2 (.getCount ^HistogramPointData point)))
                 (is (= "GET" (get-point-attr point "http.request.method")))
                 (is (= "200" (get-point-attr point "http.response.status_code")))))

             (finally (common/close client))))
         (finally (shutdown! ctx)))))))

