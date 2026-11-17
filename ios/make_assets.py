"""Generate the app's geometric icon; no customer data or external images."""
from pathlib import Path
import json
from PIL import Image, ImageDraw
root = Path(__file__).parent / 'App/Assets.xcassets'
folder = root / 'AppIcon.appiconset'
folder.mkdir(parents=True, exist_ok=True)
image=Image.new('RGB',(1024,1024),'#102039')
draw=ImageDraw.Draw(image)
draw.rounded_rectangle((144,144,880,880),radius=160,fill='#008173')
for x,y in [(260,260),(596,260),(260,596)]:
    draw.rounded_rectangle((x,y,x+168,y+168),radius=24,fill='white')
    draw.rounded_rectangle((x+35,y+35,x+133,y+133),radius=10,fill='#008173')
draw.line([(572,673),(635,736),(780,565)],fill='white',width=42,joint='curve')
image.save(folder/'Icon.png')
(folder/'Contents.json').write_text(json.dumps({'images':[{'filename':'Icon.png','idiom':'universal','platform':'ios','size':'1024x1024'}],'info':{'author':'xcode','version':1}}))
(root/'Contents.json').write_text('{"info":{"author":"xcode","version":1}}')
launch=root/'LaunchBackground.colorset';launch.mkdir(exist_ok=True)
(launch/'Contents.json').write_text(json.dumps({'colors':[{'idiom':'universal','color':{'color-space':'srgb','components':{'red':'0.055','green':'0.12','blue':'0.22','alpha':'1.0'}}}],'info':{'author':'xcode','version':1}}))
