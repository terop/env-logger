#!/bin/sh

set -e

# This a script to run various verification jobs for the Clojure code
# in this repository

echo 'Running splint'
clojure -M:splint

echo 'Running clj-kondo'
if [ "${CI}" ]; then
    apt-get install -y curl unzip
    (cd /tmp
     curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo
     chmod +x install-clj-kondo
     ./install-clj-kondo --dir /tmp)
    /tmp/clj-kondo --lint src test
else
    clj-kondo --lint src test
fi

echo 'Running cljfmt'
if [ "${CI}" ]; then
    clojure -Ttools install io.github.weavejester/cljfmt '{:git/tag "0.13.0"}' :as cljfmt
fi
clojure -Tcljfmt check

echo 'Running ruff for Python files'
if [ "${CI}" ]; then
    apt-get install -y python3.11-venv
    # shellcheck disable=SC1091
    python3 -m venv .venv && . .venv/bin/activate && pip3 install ruff && ruff check
else
    ruff check
fi
