package com.sideproject.stockledger.dto;

import jakarta.validation.constraints.Size;

/** 作廢一筆現金異動時填的原因（可不填，spec 4.9）。 */
public record VoidForm(@Size(max = 200, message = "原因最多 200 個字") String reason) {
}
