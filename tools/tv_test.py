"""Drives the Tizen app in headless Chrome like a TV remote, with a fake Xtream server."""
import json, sys, time, urllib.parse
from playwright.sync_api import sync_playwright

APP = "file:///home/claude/mashwar-tv/tizen/index.html"
OUT = "/home/claude/tvtest"
CATALOG = open(f"{OUT}/arab.json", "rb").read()
PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                    "1f15c4890000000d49444154789c63f8cfc0f01f0005000201a5f9b1d30000000049454e44ae426082")

LIVE_CATS = [{"category_id": "7", "category_name": "مصر"}, {"category_id": "3", "category_name": "رياضة"}, {"category_id": "9", "category_name": "أخبار"}]
def live(cat):
    names = {"7": ["Zed Masr 1", "Alpha Masr 2", "Masr 3"], "3": ["Sport B", "Sport A"], "9": ["News 1"]}[cat]
    return [{"stream_id": f"{cat}{i}", "name": n, "stream_icon": "http://img.test/l.png", "category_id": cat} for i, n in enumerate(names)]
VOD_CATS = [{"category_id": "50", "category_name": "أفلام عربي"}, {"category_id": "51", "category_name": "أفلام أجنبي"}]
def vod(cat): return [{"stream_id": f"{cat}{i}", "name": f"فيلم {cat}-{i}", "stream_icon": "http://img.test/p.png", "rating": "7.5", "container_extension": "mp4"} for i in range(14)]
SER_CATS = [{"category_id": "80", "category_name": "مسلسلات رمضان"}]
def ser(cat): return [{"series_id": "900", "name": "مسلسل تجريبي", "cover": "http://img.test/c.png", "plot": "قصة المسلسل"}]
INFO = {"info": {"plot": "قصة المسلسل التجريبي"}, "episodes": {"1": [{"id": "1001", "episode_num": "1", "title": "الحلقة الأولى", "container_extension": "mp4"},
                                                                   {"id": "1002", "episode_num": "2", "title": "الحلقة التانية", "container_extension": "mp4"}],
                                                             "2": [{"id": "2001", "episode_num": "1", "title": "بداية الموسم التاني", "container_extension": "mp4"}]}}

def route(r):
    url = r.request.url
    if url.startswith("file://"): return r.continue_()
    if "releases/download/catalog/arab.json" in url:
        return r.fulfill(status=200, body=CATALOG, headers={"content-type": "application/json", "access-control-allow-origin": "*"})
    if "img.test" in url or url.endswith((".png", ".jpg")): return r.fulfill(status=200, body=PNG, headers={"content-type": "image/png"})
    if "xtream.test" in url and "player_api.php" in url:
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(url).query))
        if q.get("password") != "secret": body = {"user_info": {"auth": 0}}
        else:
            a, c = q.get("action"), q.get("category_id")
            handlers = {None: lambda: {"user_info": {"auth": 1, "status": "Active", "exp_date": "1893456000", "max_connections": "2"}},
                    "get_live_categories": lambda: LIVE_CATS, "get_live_streams": lambda: live(c),
                    "get_vod_categories": lambda: VOD_CATS, "get_vod_streams": lambda: vod(c),
                    "get_series_categories": lambda: SER_CATS, "get_series": lambda: ser(c),
                    "get_series_info": lambda: INFO, "get_short_epg": lambda: {"epg_listings": []}}
            body = handlers.get(a, lambda: [])()
        return r.fulfill(status=200, body=json.dumps(body), headers={"content-type": "application/json", "access-control-allow-origin": "*"})
    return r.abort()

errors = []
with sync_playwright() as p:
    b = p.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox", "--allow-file-access-from-files", "--disable-web-security"])
    pg = b.new_page(viewport={"width": 1920, "height": 1080})
    pg.on("pageerror", lambda e: errors.append(f"PAGEERROR {e}"))
    pg.on("console", lambda m: errors.append(f"console.{m.type}: {m.text}") if m.type == "error" and "Failed to load resource" not in m.text and "webapis" not in m.text else None)
    pg.route("**/*", route)
    pg.goto(APP); pg.wait_for_timeout(800)
    def key(k, n=1, wait=250):
        for _ in range(n): pg.keyboard.press(k); pg.wait_for_timeout(wait)
    def back(): pg.evaluate("document.dispatchEvent(new KeyboardEvent('keydown',{keyCode:10009,bubbles:true}))"); pg.wait_for_timeout(400)
    def tvkey(code): pg.evaluate(f"document.dispatchEvent(new KeyboardEvent('keydown',{{keyCode:{code},bubbles:true}}))"); pg.wait_for_timeout(300)
    def shot(name): pg.screenshot(path=f"{OUT}/{name}.png"); print("shot", name, "|", pg.evaluate("document.querySelector('.focus') ? document.querySelector('.focus').textContent.slice(0,60) : '-'"))

    shot("01-home")
    key("Enter", wait=1500); shot("02-free-browse")
    key("ArrowDown", 2, 600); key("ArrowLeft", wait=500); shot("03-free-channel-focus")
    key("Enter", wait=1200); shot("04-player-error")
    key("ArrowLeft", wait=400); shot("05-player-panel")
    key("ArrowDown", 2); key("Enter", wait=800)
    for c in (51, 50): tvkey(c)
    pg.wait_for_timeout(200); shot("06-zap"); pg.wait_for_timeout(2200)
    back(); back(); back(); pg.wait_for_timeout(500); shot("07-back-home")

    # add Xtream: focus add tile (last), open form, type fields
    key("ArrowLeft", 3); key("Enter", wait=600); shot("08-add-form")
    def fill(text):
        key("ArrowDown"); key("Enter"); pg.keyboard.type(text); key("Enter")
    fill("xtream.test:8080"); fill("kareem"); fill("wrong")
    key("ArrowDown", 2); key("Enter", wait=1200); shot("09-wrong-password")
    def focus(sel, idx=0):
        pg.evaluate(f"window.__almasry.nav.focus(document.querySelectorAll({json.dumps(sel)})[{idx}])"); pg.wait_for_timeout(200)
    # correct password: retype only that field, then press the login button with the remote
    focus("#pwd"); key("Enter"); pg.keyboard.press("Control+A"); pg.keyboard.type("secret"); key("Enter")
    focus("#ok"); key("Enter", wait=1800); shot("10-home-with-xtream")
    print("folders:", pg.evaluate("Array.from(document.querySelectorAll('.folder .name')).map(e=>e.textContent)"))
    focus(".folder", 1); key("Enter", wait=1800); shot("11-xtream-live")
    key("ArrowDown", wait=1200); shot("12-xtream-live-sports")
    print("live categories order:", pg.evaluate("Array.from(document.querySelectorAll('.cats .row')).map(e=>e.textContent)"),
          "| items:", pg.evaluate("Array.from(document.querySelectorAll('.content .title')).map(e=>e.textContent)"))
    focus(".tab", 1); key("Enter", wait=1800); shot("13-movies")
    print("movie categories:", pg.evaluate("Array.from(document.querySelectorAll('.cats .row')).map(e=>e.textContent)"))
    key("ArrowLeft", wait=400); key("Enter", wait=1200); shot("14-movie-player")
    back(); pg.wait_for_timeout(1500)
    focus(".tab", 2); key("Enter", wait=1800)
    key("ArrowLeft", wait=400); key("Enter", wait=1800); shot("15-series-page")
    key("ArrowDown"); key("Enter", wait=1200); shot("16-episode-player")
    back(); back(); back(); back()
    shot("17-final")
    b.close()

print("ERRORS:", len(errors)); [print(" ", e) for e in errors[:20]]
