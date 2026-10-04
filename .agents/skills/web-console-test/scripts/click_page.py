"""Click elements of the web console page itself in a headless browser and report the requests each click sends.

For bugs in the console page's own JavaScript (a click handler that ignores taps on an icon, a button that posts nothing).
Uses the installed Microsoft Edge through Playwright (`channel="msedge"`), so no browser download is needed. See ../SKILL.md.

  uv run --no-project --with playwright python -I click_page.py <url file> <button id>... [--text]

<url file> is the file `console.sh url <package>` writes ($CONSOLE_STATE_DIR/url; it holds the token, never print it).
For each button id: one real mouse click on the centre of the icon inside the button (`svg`), then with --text one on the
right end of the button (its label) for comparison. A button the TV's current state does not show is reported as missing.
"""
import sys

from playwright.sync_api import sync_playwright

sys.stdout.reconfigure(encoding="utf-8")
args = sys.argv[1:]
if not args:
    sys.exit(__doc__)
url = open(args[0], encoding="utf-8").read().strip()
with_text = "--text" in args
ids = [a for a in args[1:] if not a.startswith("--")]

with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page(viewport={"width": 420, "height": 900}, has_touch=True)
    posts = []
    page.on("request", lambda r: posts.append(r.url.split("?")[0].split("/")[-1]) if r.method == "POST" else None)
    page.goto(url)
    page.wait_for_timeout(4000)  # first poll of the player state renders the cards

    def click(x, y):
        before = len(posts)
        page.mouse.click(x, y)
        page.wait_for_timeout(1500)
        sent = posts[before:]
        return "POST " + ", ".join(sent) if sent else "no request"

    for bid in ids:
        button = page.query_selector(f"#{bid}")
        if button is None:
            print(f"{bid}: not on the page (the TV's current state does not show it)")
            continue
        icon = page.query_selector(f"#{bid} svg")
        if icon is not None:
            box = icon.bounding_box()
            print(f"{bid}: click on the icon -> {click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)}")
        if with_text or icon is None:
            box = button.bounding_box()
            print(f"{bid}: click on the label -> {click(box['x'] + box['width'] - 8, box['y'] + box['height'] / 2)}")
    browser.close()
