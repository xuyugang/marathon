# Marathon Testing

This directory contains system integration tests of marathon in a DCOS environment.

The easiest way to run the tests is with [pipenv](https://github.com/kennethreitz/pipenv).

Given you have Python 3.5+ and pip installed simply run

```
  pip install pipenv
  pipenv run dcos cluster setup --no-check --username=<DCOS_USER> --password=<DCOS_PW> <DCOS_URL>
  pipenv run shakedown tests/system
```
