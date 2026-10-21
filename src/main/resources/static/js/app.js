// 全站共用的小功能。不用前端框架（CLAUDE.md），只寫原生 JavaScript。

// 送出前確認：表單有 data-confirm（例如作廢）時先問一次；按「取消」就不送出。
// 用 capture 階段，確保它比下面「鎖住按鈕」先執行；取消時按鈕也不會被鎖住。
document.addEventListener('submit', function (event) {
	var message = event.target.dataset.confirm;
	if (message && !window.confirm(message)) {
		event.preventDefault();
	}
}, true);

// 防止連點：按下送出後立刻鎖住表單裡的按鈕，避免同一筆資料送出兩次。
// 這只是改善體驗；真正的保證在伺服器端（資料庫唯一限制 + Service 轉成「已存在」訊息）。
document.addEventListener('submit', function (event) {
	if (event.defaultPrevented) {
		return;
	}
	event.target.querySelectorAll('button[type="submit"], button:not([type])').forEach(function (button) {
		button.disabled = true;
		button.dataset.locked = 'true';
		if (button.dataset.busyText) {
			button.dataset.originalText = button.textContent;
			button.textContent = button.dataset.busyText;
		}
	});
});

// 按「上一頁」回到表單時，瀏覽器可能還原「按鈕已鎖住」的畫面，要解鎖才能再送出。
// 每次顯示頁面都解鎖，不只 bfcache（Firefox 從一般快取重新載入時也會還原 disabled，而且 event.persisted 是 false）。
window.addEventListener('pageshow', function () {
	document.querySelectorAll('form button[type="submit"], form button:not([type])').forEach(function (button) {
		button.disabled = false;
		delete button.dataset.locked;
		if (button.dataset.originalText) {
			button.textContent = button.dataset.originalText;
		}
	});
});
