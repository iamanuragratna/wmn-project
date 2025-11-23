// src/app/services/websocket.service.ts
import { Injectable } from '@angular/core';
import { Client, Frame, IMessage, StompSubscription } from '@stomp/stompjs';
import * as SockJSRaw from 'sockjs-client';
import { Subject, BehaviorSubject } from 'rxjs';

const SockJS: any = (SockJSRaw && (SockJSRaw as any).default) ? (SockJSRaw as any).default : SockJSRaw;

/**
 * Robust STOMP over SockJS service with verbose logging for debugging.
 *
 * - Connects to the broker endpoint (use your backend SockJS endpoint)
 * - Subscribes to /topic/telemetry
 * - Emits parsed messages on messages$
 * - Emits raw bus messages on rawMessages$ for debugging
 */
@Injectable({ providedIn: 'root' })
export class WebsocketService {
  private client?: Client;
  private subscription?: StompSubscription;
  private url = 'http://localhost:8085/ws/telemetry'; // backend SockJS endpoint
  public messages$ = new Subject<any>();      // parsed {type, payload}
  public rawMessages$ = new Subject<string>(); // raw message body (for debugging)
  public status$ = new BehaviorSubject<'DISCONNECTED'|'CONNECTING'|'CONNECTED'|'ERROR'>('DISCONNECTED');

  constructor() {}

  connect(): void {
    console.log('[WS] connect called, current client:', !!this.client);
    if (this.client && (this.client as any).connected) {
      console.log('[WS] already connected, skipping.');
      return;
    }

    this.status$.next('CONNECTING');

    // create SockJS factory
    const sockFactory = () => {
      try {
        const s = new SockJS(this.url);
        console.log('[WS] created SockJS instance', s);
        return s;
      } catch (err) {
        console.error('[WS] SockJS creation failed', err);
        throw err;
      }
    };

    // create STOMP client
    this.client = new Client({
      brokerURL: undefined, // required when using webSocketFactory
      webSocketFactory: sockFactory as any,
      reconnectDelay: 5000,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 10000,
      debug: (str) => {
        // keep this verbose during debugging
        console.log('[STOMP]', str);
      },
      onConnect: (frame: Frame) => {
        console.log('[STOMP] onConnect', frame.headers);
        this.status$.next('CONNECTED');

        // subscribe to telemetry topic
        try {
          // unsubscribe if existing
          if (this.subscription) {
            try { this.subscription.unsubscribe(); } catch(e) { /*noop*/ }
            this.subscription = undefined;
          }

          this.subscription = this.client!.subscribe('/topic/telemetry', (message: IMessage) => {
            try {
              const body = message.body || '';
              console.log('[STOMP] raw message received (length=' + body.length + ')');
              this.rawMessages$.next(body);

              // Try to parse JSON. If already a JSON text with 'type'/'payload' use it directly.
              let parsed: any;
              try {
                parsed = JSON.parse(body);
              } catch (e) {
                console.warn('[STOMP] JSON parse failed, wrapping raw body', e);
                parsed = { type: 'raw', payload: body };
              }

              // If message is already {type,payload} emit as-is. Otherwise attempt to normalize.
              if (parsed && parsed.type && parsed.payload) {
                this.messages$.next(parsed);
              } else {
                // heuristic: detect telemetry feature objects and set type accordingly
                const t = parsed.type ||
                  (parsed.avgChannelBusyPercent || parsed.avgChannelBusyPercent === 0 ? 'feature_update' :
                   (parsed.forecastBusyPercent || parsed.forecastBusyPercent === 0 ? 'forecast_update' :
                    (parsed.nodeId ? 'feature_update' : 'unknown')));

                this.messages$.next({ type: t, payload: parsed });
              }
            } catch (err) {
              console.error('[STOMP] subscription handler error', err);
            }
          });

          console.log('[STOMP] subscribed to /topic/telemetry');
        } catch (err) {
          console.error('[STOMP] subscribe failed', err);
        }
      },
      onStompError: (frame) => {
        console.error('[STOMP] stomp error', frame);
        this.status$.next('ERROR');
      },
      onWebSocketClose: (ev) => {
        console.warn('[STOMP] websocket closed', ev);
        this.status$.next('DISCONNECTED');
      },
      onWebSocketError: (ev) => {
        console.error('[STOMP] websocket error', ev);
        this.status$.next('ERROR');
      }
    } as any);

    // activate the client (starts the connection)
    try {
      this.client.activate();
      console.log('[STOMP] client.activate() called');
    } catch (err) {
      console.error('[STOMP] client.activate() error', err);
      this.status$.next('ERROR');
    }
  }

  disconnect(): void {
    console.log('[WS] disconnect called');
    try {
      if (this.subscription) {
        try { this.subscription.unsubscribe(); } catch(e) { /*noop*/ }
        this.subscription = undefined;
      }
      if (this.client) {
        this.client.deactivate();
      }
      this.status$.next('DISCONNECTED');
    } catch (err) {
      console.error('[WS] disconnect error', err);
    }
  }
}
