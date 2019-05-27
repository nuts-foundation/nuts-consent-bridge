nuts-consent-bridge
===================

App to bridge gap between the Corda kotlin world and Nuts polyglot service space

.. image:: https://travis-ci.org/nuts-foundation/nuts-consent-bridge.svg?branch=master
    :target: https://travis-ci.org/nuts-foundation/nuts-consent-bridge
    :alt: Build Status

.. image:: https://readthedocs.org/projects/nuts-consent-bridge/badge/?version=latest
    :target: https://nuts-documentation.readthedocs.io/projects/nuts-consent-bridge/en/latest/?badge=latest
    :alt: Documentation Status

.. image:: https://codecov.io/gh/nuts-foundation/nuts-consent-bridge/branch/master/graph/badge.svg
    :target: https://codecov.io/gh/nuts-foundation/nuts-consent-bridge

.. image:: https://api.codacy.com/project/badge/Grade/9118a6e2254e4db0a6c1fc3725a6ed02
    :target: https://www.codacy.com/app/woutslakhorst/nuts-consent-bridge

Build is failing since Corda needs a version for test that Travis does not have (Ubuntu)

.. inclusion-marker-for-contribution

.. todo https://github.com/booksbyus/zguide/blob/master/examples/Java/asyncsrv.java
        http://zguide.zeromq.org/php:chapter3#reliable-request-reply fig 38

run tests
---------

.. code-block::

        ./gradlew check

generate code coverage report
-----------------------------

.. code-block::

        ./gradlew codeCoverageReport

This task depends on all tests from all sub-projects


generate code from api spec
---------------------------

.. code-block::

        ./gradlew codegen
