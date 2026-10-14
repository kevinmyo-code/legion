import json, base64, sys, urllib.request, pathlib, concurrent.futures as cf
key=[l.split("=",1)[1].strip() for l in open("local.properties") if l.startswith("GEMINI_API_KEY=")][0]
sp=pathlib.Path(sys.argv[1]); out=sp
src=base64.b64encode((pathlib.Path('art/seed/summer_coast_source.png')).read_bytes()).decode()
BASE=("Redraw this exact scene - same composition, same palm trees, same road, same Jeep, same flat lofi summer "
 "city-pop cartoon style and clean lineart - but at night. It must be DARK overall to minimise light in a car at "
 "night: most of the image deep navy and blue-black, low brightness, low contrast, no bright white areas, no glare. ")
jobs={
 "night_a_moonlit": BASE+"A small crescent moon and a few soft stars, the sea a dark teal with a faint silver moon path, palms as dark silhouettes, the Jeep's headlights off. Very calm.",
 "night_b_streetlights": BASE+"A few dim warm streetlights along the road casting small soft amber pools, the Jeep's tail lights a tiny dim red glow, distant town lights on the far shore as tiny dim dots.",
 "night_c_late_dusk": BASE+"Just after sunset: the last thin band of muted plum and dusky coral on the horizon, everything else deep indigo, first stars appearing, palms in silhouette.",
}
def run(name):
    body=json.dumps({"contents":[{"parts":[{"inlineData":{"mimeType":"image/png","data":src}},{"text":jobs[name]}]}],
        "generationConfig":{"responseModalities":["TEXT","IMAGE"],"imageConfig":{"aspectRatio":"16:9"}}}).encode()
    req=urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image:generateContent",data=body,
        headers={"x-goog-api-key":key,"Content-Type":"application/json"})
    try:
        r=json.load(urllib.request.urlopen(req,timeout=240))
        for p in r["candidates"][0]["content"]["parts"]:
            if "inlineData" in p:
                (out/f"{name}.png").write_bytes(base64.b64decode(p["inlineData"]["data"])); return name+": ok"
        return name+": no image"
    except urllib.error.HTTPError as e: return f"{name}: HTTP {e.code} {e.read()[:200]}"
    except Exception as e: return f"{name}: {e}"
with cf.ThreadPoolExecutor(3) as ex:
    for r in ex.map(run, jobs): print(r)
