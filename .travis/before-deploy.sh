#!/usr/bin/env bash
if [ "$TRAVIS_PULL_REQUEST" == 'false' ] && [ ! -z "$TRAVIS_TAG" ]
then
    openssl aes-256-cbc -K $encrypted_12fbd8a79a40_key -iv $encrypted_12fbd8a79a40_iv -in .travis/codesigning.asc.enc -out .travis/codesigning.asc -d
    gpg --fast-import .travis/codesigning.asc
fi