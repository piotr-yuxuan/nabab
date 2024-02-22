(defproject nabab (-> "./resources/nabab.version" slurp .trim)
  :description "Describe how data flow between core.async channels with a pinch of syntactic sugar"
  :github/private? false
  :license {:name "European Union Public License 1.2 or later"
            :url "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"
            :distribution :repo}
  :scm {:name "git"
        :url "https://github.com/piotr-yuxuan/nabab"}
  :pom-addition [:developers [:developer
                              [:name "胡雨軒 Петр"]
                              [:url "https://github.com/piotr-yuxuan"]]]
  :dependencies [[org.clojure/core.async "1.6.681"]]
  :plugins [[lein-cljsbuild "1.1.8"]
            [lein-shell "0.5.0"]]
  :aot :all
  :profiles {:github {:github/topics ["core-async" "clojure" "async" "channels"]
                      :github/private? false}
             :provided {:dependencies [[org.clojure/clojure "1.12.0-alpha8"]
                                       [io.confluent/kafka-avro-serializer "7.6.0"]
                                       [org.apache.avro/avro "1.11.3"]]}
             :dev {:global-vars {*warn-on-reflection* true}}}
  :repositories [["confluent" {:url "https://packages.confluent.io/maven/"}]]
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url "https://clojars.org/repo"
                                    :username :env/WALTER_CLOJARS_USERNAME
                                    :password :env/WALTER_CLOJARS_PASSWORD}]
                        ["github" {:sign-releases false
                                   :url "https://maven.pkg.github.com/piotr-yuxuan/nabab"
                                   :username :env/GITHUB_ACTOR
                                   :password :env/WALTER_GITHUB_PASSWORD}]])
