// stomp-test.js
const SockJS = require('sockjs-client');
const { Client } = require('@stomp/stompjs');

const endpoint = 'http://localhost:8085/ws/telemetry'; // adjust to your app's endpoint
console.log("Connecting to:", endpoint);

// SockJS factory for Node
const socketFactory = () => new SockJS(endpoint);

const client = new Client({
  webSocketFactory: socketFactory,
  debug: (msg) => console.log("[STOMP]", msg),

  onConnect: () => {
    console.log("✔ STOMP connected!");

    client.subscribe('/topic/telemetry', msg => {
      console.log(">>> MESSAGE RECEIVED:");
      try { console.log(JSON.parse(msg.body)); }
      catch { console.log(msg.body); }
    });

    console.log("Subscribed to /topic/telemetry");
  },

  onStompError: (err) => {
    console.error("❌ STOMP error", err);
  }
});

client.activate();
