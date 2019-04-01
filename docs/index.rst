.. _nuts-consent-bridge:

Nuts Consent Bridge
===================

The goald of the *Nuts Consent Bridge* is abstract away from the Corda specific classes and logic. It also exposes logic and data language agnostic.
Corda is written in Java/Kotlin. The *Nuts Corda Bridge* exposes endpoints using `ZeroMQ <https://zeromq.org>`_. ZeroMQ has support in many languages.
There are two main interfaces on the bridge: the publish/subscribe endpoint and the request/response endpoint. Both are described in their own chapter.

.. toctree::
   :maxdepth: 2
   :caption: Contents:
   :glob:

   pages/*


