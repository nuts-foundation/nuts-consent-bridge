.. _nuts-consent-bridge-configuration:

Nuts consent bridge configuration
#################################

.. marker-for-readme

The *Nuts Consent Bridge* application is a Spring boot application. Therefore all `Spring methods of configuring <https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-external-config.html>`_ can be used including:

- Using a runtime JVM parameter specifying the spring configuration file: ``java -jar myproject.jar --spring.config.location=/tmp/overrides.properties``
- Using environment variables, replacing all camelCasing and dots with underscores. So ``nuts.consent.nats.address`` becomes ``NUTS_CONSENT_NATS_ADDRESS``

=====================================   =====================   =====================================================================
Property                                Default                 Description
=====================================   =====================   =====================================================================
nuts.consent.nats.address               nats://localhost:4222   The Nats address for events from and to *Nuts Service Space*.
nuts.consent.nats.cluster               test-cluster            The Nats clusterID.
nuts.consent.rpc.host                   localhost               The host running the Consent Cordapp.
nuts.consent.rpc.port                   7887                    Port for Consent Cordapp.
nuts.consent.rpc.user                   admin                   Configured user on the RPC methods of the Consent Cordapp node.
nuts.consent.rpc.password               nuts                    ^^ same, but password ^^
nuts.consent.rpc.retryIntervalSeconds   5                       Cooldown period before trying to reconnect to node.
nuts.consent.rpc.retryCount             0                       How many times to reconnect (0 for infinite).
nuts.consent.registry.url               http://localhost:8088   The address + path where the Nuts registry is running.
nuts.consent.schedule.delay             15 * 60 * 1000          Delay between scheduled health checks in milliseconds.
nuts.consent.schedule.initial_delay     1000                    Delay between scheduled health checks.
nuts.event.meta.location                .                       Location where to store the timestamps of latest received Corda event
=====================================   =====================   =====================================================================

Docker
******

*Nuts Consent Bridge* doesn't have a DB but it does store a bunch of timestamps on disk. These timestamps are used to start listeners and receive messages/events beginning at the point in time when the application was closed. When using docker these files must be mounted otherwise each new version will consume all previous messages since the beginning of time (epoch = 0).

The default location for `nuts.event.meta.location == .`. This has to be changed to something else. An example config would be:

.. code-block:: yaml

    nuts.event.meta.location: /var/nuts/timestamps

The docker run command would then be:

.. code-block:: shell

    docker run \
      --name bridge \
      -w /opt/nuts \
      -v {{config_dir}}/application.properties:/opt/nuts/application.properties \
      -v {{data_dir}}:/var/nuts/timestamps \
      -p 8080:8080 \
      -d \
      nuts-consent-bridge:latest-dev

So besides the `application.properties`, a data dir has to be mounted as well.
