(defproject nabab "1.0.2"
  :description "Describe how data flow between core.async channels with a pinch of syntactic sugar"
  :github/private? false
  :url "https://github.com/piotr-yuxuan/nabab"
  :dependencies [[org.clojure/core.async "0.4.474"]
                 [org.clojure/clojure "1.10.0-alpha8"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-shell "0.5.0"]]
  :min-lein-version "2.5.3"
  :license {:name "GNU GPL, version 3, 29 June 2007"
            :url "https://www.gnu.org/licenses/gpl-3.0.txt"
            :addendum "GPL_ADDITION.md"}
  :source-paths ["src"]
  :aot :all
  :clean-targets ^{:protect false} ["target"]
  :profiles {})
