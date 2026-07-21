(def i18n-version "1.0.4")
(def slf4j-version "2.0.18")

(defproject org.openvoxproject/http-client "2.4.1-SNAPSHOT"
  :description "HTTP client wrapper"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :min-lein-version "2.9.1"

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort

  ;; Generally, try to keep version pins in :managed-dependencies and the libraries
  ;; this project actually uses in :dependencies, inheriting the version from
  ;; :managed-dependencies. This prevents endless version conflicts due to deps of deps.
  ;; Renovate should keep the versions largely in sync between projects.
  :managed-dependencies [[org.clojure/clojure "1.12.5"]
                         [cheshire "6.2.0"]
                         [commons-codec "1.22.0"]
                         [commons-io "2.22.0"]
                         [io.dropwizard.metrics/metrics-core "3.2.6"]
                         [org.apache.httpcomponents/httpasyncclient "4.1.5"]
                         [org.bouncycastle/bcpkix-jdk18on "1.85"]
                         [org.bouncycastle/bcpkix-fips "1.0.8"]
                         [org.bouncycastle/bc-fips "1.0.2.6"]
                         [org.bouncycastle/bctls-fips "1.0.19"]
                         [org.openvoxproject/i18n ~i18n-version]
                         [org.openvoxproject/kitchensink "3.5.7" :classifier "test"]
                         [org.openvoxproject/ring-middleware "2.2.0"]
                         [org.openvoxproject/ssl-utils "3.7.0"]
                         [org.openvoxproject/trapperkeeper "5.0.4"]
                         [org.openvoxproject/trapperkeeper "5.0.4" :classifier "test"]
                         [org.openvoxproject/trapperkeeper-webserver "12.1.0"]
                         [org.openvoxproject/trapperkeeper-webserver "12.1.0" :classifier "test"]
                         [org.slf4j/slf4j-api ~slf4j-version]
                         [org.slf4j/jul-to-slf4j ~slf4j-version]
                         [prismatic/schema "1.4.1"]]

  :dependencies [[org.clojure/clojure]
                 [commons-io]
                 [io.dropwizard.metrics/metrics-core]
                 [org.apache.httpcomponents/httpasyncclient]
                 [org.openvoxproject/ssl-utils]
                 [org.openvoxproject/i18n]
                 [org.slf4j/jul-to-slf4j]
                 [prismatic/schema]]

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :jar-exclusions [#".*\.java$"]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the source code (including the java source). Downstream projects can then
  ;; depend on this source jar using a :classifier in their :dependencies.
  :classifiers [["sources" :sources-jar]]

  :profiles {:provided {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}
             :defaults {:dependencies [[cheshire]
                                       [org.openvoxproject/kitchensink :classifier "test"]
                                       [org.openvoxproject/trapperkeeper]
                                       [org.openvoxproject/trapperkeeper :classifier "test"]]
                        :resource-paths ["dev-resources"]
                        :jvm-opts ["-Djava.util.logging.config.file=dev-resources/logging.properties"]}
             :dev-deps  {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}
             :dev [:defaults :dev-deps :test]
             :test {:pedantic? :warn
                    :dependencies [[org.openvoxproject/trapperkeeper-webserver]
                                  [org.openvoxproject/trapperkeeper-webserver :classifier "test"]
                                  [org.openvoxproject/ring-middleware]]}
             :fips-deps {:dependencies [[org.bouncycastle/bcpkix-fips]
                                        [org.bouncycastle/bc-fips]
                                        [org.bouncycastle/bctls-fips]]
                         ;; this only ensures that we run with the proper profiles
                         ;; during testing. This JVM opt will be set in the puppet module
                         ;; that sets up the JVM classpaths during installation.
                         :jvm-opts ~(let [version (System/getProperty "java.version")
                                          [major minor _] (clojure.string/split version #"\.")
                                          unsupported-ex (ex-info "Unsupported major Java version. Expects 17, 21 or 25."
                                                           {:major major
                                                            :minor minor})]
                                      (condp = (java.lang.Integer/parseInt major)
                                        17 ["-Djava.security.properties==dev-resources/jdk17-fips-security"]
                                        21 ["-Djava.security.properties==dev-resources/jdk21-fips-security"]
                                        25 ["-Djava.security.properties==dev-resources/jdk25-fips-security"]
                                        (throw unsupported-ex)))}
             :fips [:defaults :fips-deps]
             :sources-jar {:java-source-paths ^:replace []
                           :jar-exclusions ^:replace []
                           :source-paths ^:replace ["src/clj" "src/java"]}}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/CLOJARS_USERNAME
                                     :password :env/CLOJARS_PASSWORD
                                     :sign-releases false}]]

  :lein-release {:scm :git
                 :deploy-via :lein-deploy}

  :plugins [[jonase/eastwood "1.4.3" :exclusions [org.clojure/clojure]]
            [org.openvoxproject/i18n ~i18n-version]]

  :eastwood {:continue-on-exception true
             :exclude-namespaces [;; linting this test throws and exception as test-utils/load-test-config
                                  ;; requires the addition of the config in /testutils, excluding for now
                                  puppetlabs.orchestrator.integration.migration-errors-test
                                  ;; The BoltClient protocol has more than 20 functions and therefore an exception is thrown
                                  ;; when compiling it for linting https://github.com/jonase/eastwood/issues/344
                                  puppetlabs.orchestrator.bolt.client]
             :exclude-linters [:no-ns-form-found :reflection :deprecations]
             :ignored-faults {:def-in-def {puppetlabs.http.client.async-plaintext-test [{:line 278}]}}}
)
