.. _nuts-consent-bridge-technical:

Nuts consent bridge technical info
##################################

The publish subscribe endpoint is for receiving events about new or consumed states inside the *Nuts Consent Cordapp*.
These events can then be used to update the internal state in *Service Space* or can be used to perform validations.

ZeroMQ is non-persistant. The main method of disaster-recovery is to reinitialise the stream with the timestamp of the last received event.
This will do a query on the Corda Vault with the given timestamp. Any missed updates will be read from the Vault before the new events are published.
Its up to the subscriber to store the timestamp somewhere.

Stream initiation
*****************

Clients that are interested in published events connect to the exposed ZeroMQ ``publisherAddress`` with a ZeroMQ ``SUB`` socket.
A topic must be chosen to make sure all published events are at the right client. The ZeroMQ``socket.subscribe`` method can be used for this.
Before any events are send, the client must send a request to the bridge with a moment in time. All events published after this moment will be send, including historic events.
To do this, the client must POST the following message to ``/api/consent/event_stream``:

.. code-block:: json

    {
        "topic": "some_unique_name",
        "epoch", "0"
    }

.. important::

    The subscribe socket must be connected and a topic must have been registered before the initiation request is send.

If everything goes well, the other side will respond with an ``OK`` response.


Events format
*************

Events are received in the form

``TOPIC:STATE:STATE_LINEAR_ID:CHANGE``

Where ``topic`` is the chosen topic for the queue, ``STATE`` represents the type of Corda state that is produced or consumed. In the Nuts case this is either a
``ConsentRequestState`` or ``ConsentState``. The ``STATE_LINEAR_ID`` is the ID of the state that has changed. This ID would be used in a call from *Service Space*.
``CHANGE`` can either have the value ``produced`` or ``consumed``, indicating that the state has been added or removed.


Error handling
**************

If anything goes wrong with the connecting queue, the only option is to close it and restart the stream beginning at the last epoch that has been successfully processed.
This means that the subscribing application MUST maintain a *last known epoch*. Using this approach means that no other persistent queueing technology is needed.