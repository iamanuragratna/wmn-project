package com.wmn.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventMessage {
    private String type; // feature_update | forecast_update | optimizer_plan | command_status
    private Object payload;

    public EventMessage() {}
    public EventMessage(String type, Object payload) {
        this.type = type;
        this.payload = payload;
    }
    // getters/setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
}
