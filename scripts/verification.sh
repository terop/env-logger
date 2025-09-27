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
    clojure -Ttools install io.github.weavejester/cljfmt '{:git/tag "0.13.4"}' :as cljfmt
fi
clojure -Tcljfmt check

# Only run ruff when called from the env-logger repository to avoid ruff failures
# when called from other repositories
if [ "${CI}" ] && [ ${CIRCLE_PROJECT_REPONAME} = 'env-logger' ] || \
       [ $(basename $(pwd)) = 'env-logger' ]; then
    echo 'Running ruff for Python files'
    if [ "${CI}" ]; then
        apt-get install -y python3.13-venv
        # shellcheck disable=SC1091
        python3 -m venv .venv && . .venv/bin/activate && pip3 install ruff && ruff check
    else
        ruff check
    fi
fi

# Only run ESLint locally due to difficulties to install it on CI machines
if [ -z "${CI}" ]; then
    echo 'Running ESLint for JavaScript files'
    npx eslint src/
fi
