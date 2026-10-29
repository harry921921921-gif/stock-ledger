package com.sideproject.stockledger.dto;

/**
 * 引導每一步上方「已儲存的○○」清單的一列（設計文件 2026-10-25-onboarding 3.2）：id 給「修改」用，
 * label 是 Service 組好的顯示文字（例如「玉山交割戶（證券交割戶）」），模板只顯示。
 */
public record SavedRow(Long id, String label) {

}
