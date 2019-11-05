.. _nuts-consent-bridge-configuration:

Nuts consent bridge configuration
#################################

.. marker-for-readme

The *Nuts Consent Bridge* application is a Spring boot application. Therefore all `Spring methods of configuring <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-external-config.html>`_ can be used including:

- Using a runtime JVM parameter specifying the spring configuration file: ``java -jar myproject.jar --spring.config.location=/tmp/overrides.properties``
- Using environment variables, replacing all camelCasing and dots with underscores. So ``nuts.consent.zmq.publisherAddress`` becomes ``NUTS_CONSENT_ZMQ_PUBLISHER_ADDRESS``

=====================================   =====================   ================================================================
Property                                Default                 Description
=====================================   =====================   ================================================================
nuts.consent.nats.address               nats://localhost:4222   The Nats address for events from and to *Nuts Service Space*
nuts.consent.nats.cluster               test-cluster            The Nats clusterID
nuts.consent.rpc.host                   localhost               The host running the Consent Cordapp.
nuts.consent.rpc.port                   7887                    Port for Consent Cordapp.
nuts.consent.rpc.user                   admin                   Configured user on the RPC methods of the Consent Cordapp node.
nuts.consent.rpc.password               nuts                    ^^ same, but password ^^
nuts.consent.rpc.retryIntervalSeconds   5                       Cooldown period before trying to reconnect to node.
nuts.consent.rpc.retryCount             0                       How many times to reconnect (0 for infinite)
nuts.consent.registry.url               http://localhost:8088   The address + path where the Nuts registry is running
=====================================   =====================   ================================================================
