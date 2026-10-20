package com.sideproject.stockledger.dto;

/**
 * 使用者管理頁的一列。故意不含密碼雜湊；角色與狀態由 Service 先轉成中文，模板只負責顯示。
 *
 * @param statusLabel 正常／鎖定中／已停用
 */
public record UserRow(Long id, String username, String displayName, String roleLabel, String statusLabel) {
}
