import json, base64, sys, urllib.request, pathlib, concurrent.futures as cf
key=[l.split("=",1)[1].strip() for l in open("local.properties") if l.startswith("GEMINI_API_KEY=")][0]
out=pathlib.Path(sys.argv[1])
src=base64.b64encode(pathlib.Path('art/seed/summer_coast_source.png').read_bytes()).decode()
STYLE=("Keep the exact flat lofi summer 1980s Japanese city-pop cartoon style of Hiroshi Nagai and Eizin Suzuki: flat colour, "
 "clean simple lineart, same composition, same palm trees, same curving coastal road, same sea. No text, no logo, no border. "
 "Keep large calm open areas of sky and road, because dashboard UI sits on top. ")
JEEP=("The Jeep is a boxy 1998 Jeep Cherokee XJ painted GLOSS BLACK: black body, black bumpers and grille, chrome-free, "
 "with clean light rim highlights along its roof, hood and door edges so its boxy shape reads clearly. ")
NIGHT=("At night, but it must stay READABLE: a clear moonlit blue-hour night where every shape is easy to see - the road, "
 "the palms, the sea, the shoreline and the Jeep are all clearly visible. Mid-tone blues and soft indigo, NOT near-black, "
 "no crushed shadows. Still calm and low-glare (it is shown in a car at night): no pure white areas, no lens flare. ")
jobs={
 "day_a_same_scene": STYLE+JEEP+"Redraw this exact summer afternoon scene, only changing the Jeep to the black one described. Bright clear sky blue, turquoise sea, puffy cream clouds.",
 "day_b_jeep_closer": STYLE+JEEP+"Same summer afternoon scene, but the black Jeep is a little larger and closer, driving along the road toward the viewer, with a soft shadow under it. Bright clear sky blue, turquoise sea, puffy cream clouds.",
 "day_c_golden_hour": STYLE+JEEP+"Same scene in late golden-hour summer light: warm peach and coral glow low in the sky fading to clear blue above, the sea turquoise with warm reflections, the black Jeep catching warm orange highlights on its edges.",
 "night_a_moonlit": STYLE+JEEP+NIGHT+"A big bright full moon over the sea casting a wide silver moon path on the water, the sky a luminous deep blue with soft stars and a few moonlit clouds, palms lit by moonlight, the black Jeep's headlights on with a soft warm beam on the road.",
 "night_b_streetlights": STYLE+JEEP+NIGHT+"Warm streetlights along the seawall casting generous amber pools of light across the road and sidewalk, the black Jeep under a streetlight with amber highlights on its body, headlights on, tail lights glowing red, sky a soft glowing navy, town lights twinkling across the bay.",
 "night_c_blue_hour": STYLE+JEEP+NIGHT+"Twenty minutes after sunset: the sky a gradient from soft periwinkle and lavender at the top to dusty pink and coral along the horizon, the sea reflecting it, palms dark teal but detailed, the black Jeep with headlights on and a soft lavender sheen on its body.",
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
with cf.ThreadPoolExecutor(6) as ex:
    for r in ex.map(run, jobs): print(r)
