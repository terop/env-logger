#!/bin/sh

set -e

# This a script to run various verification jobs for code
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
    clojure -Ttools install io.github.weavejester/cljfmt '{:git/tag "0.16.5"}' :as cljfmt
fi
clojure -Tcljfmt check

# Only run ruff when called from the env-logger repository to avoid ruff failures
# when called from other repositories
# shellcheck disable=SC2046,SC2086
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

if [ "${CI}" ]; then
    apt-get install -y npm
    npm install
fi
echo 'Running ESLint for JavaScript files'
npx eslint src/

# shellcheck disable=SC2046,SC2086
if [ "${CI}" ] && [ ${CIRCLE_PROJECT_REPONAME} = 'env-logger' ] || \
       [ $(basename $(pwd)) = 'env-logger' ]; then
    echo 'Building frontend JS bundles'
    npm run build:js

    echo 'Running frontend unit tests'
    npm test
fi
