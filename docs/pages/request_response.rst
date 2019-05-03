.. _nuts-consent-bridge-req-resp:

ZeroMQ Request Response Endpoint
================================



Configuration
-------------

The *Nuts Consent Bridge* application is a Spring boot application. Therefore all `Spring methods of configuring <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-external-config.html>`_ can be used including:

- Using a runtime JVM parameter specifying the spring configuration file: ``java -jar myproject.jar --spring.config.location=/tmp/overrides.properties``
- Using environment variables, replacing all camelCasing and dots with underscores. So ``nuts.consent.zmq.publisherAddress`` becomes ``NUTS_CONSENT_ZMQ_PUBLISHER_ADDRESS``

The following configuration properties are available:

=====================================   =====================   ================================================================
Property                                Default                 Description
=====================================   =====================   ================================================================
nuts.consent.registry.url               http://localhost:8081   The address + path where the Nuts registry is running
=====================================   =====================   ================================================================