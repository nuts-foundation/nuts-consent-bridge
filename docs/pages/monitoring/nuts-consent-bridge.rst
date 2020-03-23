.. _nuts-consent-bridge-monitoring:

Nuts consent bridge monitoring
##############################

Basic service health
********************

A status endpoint is provided to check if the service is running and if the web server has been started.
The endpoint is available over http so it can be used by a wide range of health checking services.
It does not provide any information on the individual engines running as part of the executable.
The main goal of the service is to give a YES/NO answer for if the service is running?

.. code-block:: text

    GET /status

It'll return an "OK" response with a 200 status code.

Basic diagnostics
*****************

.. code-block:: text

    GET /status/diagnostics

It'll return some text displaying the current status of the various services

.. code-block:: text

    General status: DOWN
    nutsEventListener=UP {}
    nutsEventPublisher=UP {}
    &cordaRPCClientFactory=DOWN {}
    cordaNotaryPing=UP {}
    cordaRandomPing=UP {}

Extensive diagnostics
*********************

The bridge uses `Spring Actuator <https://docs.spring.io/spring-boot/docs/current/reference/html/production-ready-features.html>`_ for health monitoring. A basic overview can be viewed at:

.. code-block:: text

    GET /actuator/health

It'll return some json displaying the current status of the various services

.. code-block:: json

    {"status":"DOWN","details":{"nutsEventListener":{"status":"UP"},"nutsEventPublisher":{"status":"UP"},"&cordaRPCClientFactory":{"status":"DOWN"}}}

Docker monitoring
*****************

A docker `HEALTHCHECK` is available on the image, it runs `curl localhost:8080/status`.
This can be connected to your favourite monitoring software. Output can be checked by using `docker inspect --format='{{json .State.Health}}' container`
