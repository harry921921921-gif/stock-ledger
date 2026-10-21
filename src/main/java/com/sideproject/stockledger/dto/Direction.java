package com.sideproject.stockledger.dto;

/** 一筆異動讓淨資產增加、減少，或不影響（互轉、初始現金餘額）。模板依它決定正負號與顏色。 */
public enum Direction {
	IN, OUT, NEUTRAL
}
