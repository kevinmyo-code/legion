import json, base64, sys, urllib.request, pathlib, concurrent.futures as cf
key=[l.split("=",1)[1].strip() for l in open("local.properties") if l.startswith("GEMINI_API_KEY=")][0]
out=pathlib.Path(sys.argv[1])
STYLE=("Lofi summer cartoon in the 1980s Japanese city-pop style of Hiroshi Nagai and Eizin Suzuki: flat colour, "
 "clean simple lineart, soft sunlit shading, a warm nostalgic summer-afternoon feel. Palette of clear sky blue, "
 "pool turquoise, sun-faded coral pink, butter yellow and cream white, gentle and bright, never dark, never neon, "
 "never cyberpunk, never 3D, never painterly. No text, no logo, no border.")
MOOSE="Uncle Moose, a big friendly anthropomorphic moose with broad antlers, a long droopy nose and a grumpy-but-kind half-lidded expression, wearing human clothes"
jobs={
 "moose_a_hawaiian": ("1:1", "Head-and-shoulders portrait of "+MOOSE+": a faded coral Hawaiian shirt and tinted aviator sunglasses pushed up on his forehead, one eyebrow raised in mild disapproval. "+STYLE+" Plain soft sky-blue background, character centered, filling the frame."),
 "moose_b_cardigan": ("1:1", "Head-and-shoulders portrait of "+MOOSE+": a cream knit cardigan over a pale yellow polo, reading glasses on the end of his nose, a small reluctant smile. "+STYLE+" Plain soft cream background, character centered, filling the frame."),
 "moose_c_driver_cap": ("1:1", "Head-and-shoulders portrait of "+MOOSE+": a worn trucker cap perched between his antlers, a short-sleeve blue work shirt with a name patch, arms crossed, deadpan look. "+STYLE+" Plain soft turquoise background, character centered, filling the frame."),
 "bg_a_coast_road": ("16:9", "Wide summer afternoon scene: an empty coastal road lined with tall palm trees running along a calm turquoise sea, a boxy 1998 Jeep Cherokee XJ parked small at the roadside, big puffy clouds in a clear blue sky. Calm and uncluttered, with large open areas of sky and road for dashboard UI on top. "+STYLE),
 "bg_b_poolside": ("16:9", "Wide summer scene in the Hiroshi Nagai manner: a quiet poolside with a white modernist building, a single palm tree, long clean shadows, a still turquoise pool and a deep blue sky. Minimal and spacious, large calm open areas for UI on top. "+STYLE),
 "bg_c_sunset_drive": ("16:9", "Wide golden-hour summer scene: a gentle hilltop road overlooking a small seaside town and a harbour, soft coral and peach sunset sky fading to pale blue, a boxy 1998 Jeep Cherokee XJ small on the road. Calm, lots of open sky for UI on top. "+STYLE),
}
def run(name):
    ratio,prompt=jobs[name]
    for model in ["gemini-3.1-flash-image","gemini-2.5-flash-image"]:
        for cfg in ({"responseModalities":["TEXT","IMAGE"],"imageConfig":{"aspectRatio":ratio}},{"responseModalities":["TEXT","IMAGE"]}):
            body=json.dumps({"contents":[{"parts":[{"text":prompt}]}],"generationConfig":cfg}).encode()
            req=urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",data=body,
                headers={"x-goog-api-key":key,"Content-Type":"application/json"})
            try:
                r=json.load(urllib.request.urlopen(req,timeout=180))
                for p in r["candidates"][0]["content"]["parts"]:
                    if "inlineData" in p:
                        f=out/f"{name}.png"; f.write_bytes(base64.b64decode(p["inlineData"]["data"])); return f"{name}: ok {model} cfg={'ratio' if 'imageConfig' in cfg else 'plain'}"
            except urllib.error.HTTPError as e:
                last=f"HTTP {e.code} {e.read()[:150]}"
            except Exception as e:
                last=str(e)[:150]
    return f"{name}: FAIL {last}"
with cf.ThreadPoolExecutor(6) as ex:
    for r in ex.map(run, jobs): print(r)
