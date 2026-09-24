// Ponte tra l'app Android e l'SDK VDO.Ninja (canale dati P2P, niente audio/video).
// Kotlin chiama window.wp.*, gli eventi tornano a Kotlin via NuvioWatchParty.onEvent(json).
(function () {
    'use strict';

    var sdk = null;
    var discovery = null;
    var peers = {};

    function emit(event) {
        try {
            window.NuvioWatchParty.onEvent(JSON.stringify(event));
        } catch (e) {
            console.error('watchparty emit failed', e);
        }
    }

    function peerCount() {
        return Object.keys(peers).length;
    }

    async function leave() {
        var current = sdk;
        sdk = null;
        peers = {};
        if (discovery) {
            try { discovery.stop(); } catch (e) { /* ignorato */ }
            discovery = null;
        }
        if (current) {
            try { await current.disconnect(); } catch (e) { /* ignorato */ }
        }
    }

    async function join(room, password, label) {
        await leave();
        var instance = new VDONinjaSDK({ salt: 'vdo.ninja', password: password, label: label });
        sdk = instance;

        instance.addEventListener('dataChannelOpen', function (e) {
            if (sdk !== instance) return;
            var uuid = e.detail.uuid;
            if (!peers[uuid]) {
                peers[uuid] = true;
                emit({ type: 'peerJoined', uuid: uuid, count: peerCount() });
            }
        });
        instance.addEventListener('peerDisconnected', function (e) {
            if (sdk !== instance) return;
            var uuid = e.detail.uuid;
            if (peers[uuid]) {
                delete peers[uuid];
                emit({ type: 'peerLeft', uuid: uuid, count: peerCount() });
            }
        });
        instance.addEventListener('dataReceived', function (e) {
            if (sdk !== instance) return;
            var data = e.detail.data;
            if (!data || data.app !== 'nuvio-watchparty') return;
            emit({ type: 'message', uuid: e.detail.uuid, data: data });
        });
        instance.addEventListener('disconnected', function (e) {
            if (sdk !== instance) return;
            emit({ type: 'signaling', state: 'disconnected', willReconnect: !!(e.detail && e.detail.willReconnect) });
        });
        instance.addEventListener('reconnected', function () {
            if (sdk !== instance) return;
            emit({ type: 'signaling', state: 'connected' });
        });

        try {
            discovery = await instance.autoConnect({ room: room, password: password, label: label, mode: 'full', view: { audio: false, video: false } });
            if (sdk !== instance) return;
            emit({ type: 'joined', room: room });
        } catch (err) {
            if (sdk !== instance) return;
            emit({ type: 'error', message: String(err && err.message || err) });
        }
    }

    function send(json, uuid) {
        if (!sdk) return false;
        var payload = JSON.parse(json);
        payload.app = 'nuvio-watchparty';
        return sdk.sendData(payload, uuid || undefined);
    }

    window.wp = { join: join, leave: leave, send: send };
    emit({ type: 'ready' });
})();
