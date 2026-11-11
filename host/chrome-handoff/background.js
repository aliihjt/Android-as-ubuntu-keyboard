// Fedora Pad Handoff: tells Fedora Pad (on this computer only) which page is
// open in the front Chrome window, so the tablet and iPhone can continue it.
// Nothing leaves the computer from here; private (incognito) windows are skipped.
const HOST = "http://127.0.0.1:7783/tab";
let last = "", timer = null;

function report() {
  clearTimeout(timer);
  timer = setTimeout(async () => {  // wait for the page to settle (redirects, title)
    try {
      const [tab] = await chrome.tabs.query({active: true, lastFocusedWindow: true});
      if (!tab || tab.incognito || !/^https?:/.test(tab.url || "")) return;
      const key = tab.url + "\n" + (tab.title || "");
      if (key === last) return;
      last = key;
      await fetch(HOST, {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({url: tab.url, title: tab.title || ""})});
    } catch (e) {
      last = "";  // Fedora Pad isn't running: try again next time
    }
  }, 1200);
}

chrome.tabs.onActivated.addListener(report);
chrome.tabs.onUpdated.addListener((id, change, tab) => { if (tab.active && (change.url || change.title)) report(); });
chrome.windows.onFocusChanged.addListener(w => { if (w !== chrome.windows.WINDOW_ID_NONE) report(); });
chrome.runtime.onStartup.addListener(report);
chrome.runtime.onInstalled.addListener(report);
