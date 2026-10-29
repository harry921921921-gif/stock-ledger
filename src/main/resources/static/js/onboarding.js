// 第一次使用引導唯一的 JavaScript（設計文件 2026-10-25-onboarding 3.2、7a-1）：
// 輸入框裡按 Enter（包含注音輸入法選字時的 Enter）不送出表單，只有用滑鼠或 Tab 移到按鈕上按才會儲存或換頁。
document.addEventListener('keydown', function (event) {
	if (event.key === 'Enter' && event.target.matches('input')) {
		event.preventDefault();
	}
});
