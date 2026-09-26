package com.paymentsystem.dtos;

import lombok.Value;

@Value
public class SystemStatusResponse {

    String application;
    String mode;
}