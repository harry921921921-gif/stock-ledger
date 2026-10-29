package com.sideproject.stockledger.dto;

/** 頁面標題下方的一則提醒（設計文件 2026-10-25-onboarding 3.3）。text 顯示的文字，url 點了前往的位置。 */
public record PageNotice(String text, String url) {
}
