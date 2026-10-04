import json, zipfile, shutil, struct, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT / "build"
OUT = ROOT / "downloads"
if WORK.exists():
    shutil.rmtree(WORK)
WORK.mkdir(parents=True)
OUT.mkdir(parents=True, exist_ok=True)

bp = WORK / "Milly_Behavior"
rp = WORK / "Milly_Resources"
for p in [bp/"entities", bp/"scripts", rp/"entity", rp/"models"/"entity", rp/"render_controllers", rp/"textures"/"entity", rp/"texts"]:
    p.mkdir(parents=True, exist_ok=True)

BP_H="4f91729d-9b4b-4c2c-a9ac-2d8dedaa2a61"
BP_D="b15f10db-a104-4cee-8212-8707d0e758b1"
BP_S="096a2d57-fbb2-46bc-9ee7-bbcfa438f159"
RP_H="8e99fb46-a864-4090-8097-b88b82fc2227"
RP_M="6ce4b3c7-e4c8-4a69-ad88-a9d8543128e4"

(bp/"manifest.json").write_text(json.dumps({
 "format_version":2,
 "header":{"name":"Milly AI Companion","description":"Milly AI companion for Minecraft Bedrock.","uuid":BP_H,"version":[1,2,0],"min_engine_version":[1,26,0]},
 "modules":[
   {"type":"data","uuid":BP_D,"version":[1,2,0]},
   {"type":"script","language":"javascript","entry":"scripts/main.js","uuid":BP_S,"version":[1,2,0]}
 ],
 "dependencies":[{"uuid":RP_H,"version":[1,2,0]},{"module_name":"@minecraft/server","version":"2.10.0"}],
 "metadata":{"authors":["Arcade Home Labs"]}
}, indent=2), encoding="utf-8")

(rp/"manifest.json").write_text(json.dumps({
 "format_version":2,
 "header":{"name":"Milly AI Companion Resources","description":"Milly visuals.","uuid":RP_H,"pack_scope":"world","version":[1,2,0],"min_engine_version":[1,26,0]},
 "modules":[{"type":"resources","uuid":RP_M,"version":[1,2,0]}],
 "metadata":{"authors":["Arcade Home Labs"]}
}, indent=2), encoding="utf-8")

(bp/"entities"/"milly.json").write_text(json.dumps({
 "format_version":"1.21.0",
 "minecraft:entity":{
   "description":{"identifier":"arcade:milly","is_spawnable":True,"is_summonable":True,"is_experimental":False},
   "component_groups":{
     "arcade:follow":{"minecraft:behavior.follow_owner":{"priority":2,"speed_multiplier":1.15,"start_distance":6,"stop_distance":2}},
     "arcade:stay":{"minecraft:movement":{"value":0.0}}
   },
   "components":{
     "minecraft:type_family":{"family":["milly","companion"]},
     "minecraft:nameable":{"always_show":True,"allow_name_tag_renaming":False},
     "minecraft:persistent":{},
     "minecraft:health":{"value":30,"max":30},
     "minecraft:collision_box":{"width":0.6,"height":1.8},
     "minecraft:movement":{"value":0.28},
     "minecraft:movement.basic":{},
     "minecraft:navigation.walk":{"can_path_over_water":True,"avoid_damage_blocks":True,"can_open_doors":True,"can_pass_doors":True},
     "minecraft:jump.static":{},
     "minecraft:physics":{},
     "minecraft:tameable":{"probability":1.0,"tame_items":["minecraft:cookie"],"tame_event":{"event":"arcade:tamed","target":"self"}},
     "minecraft:sittable":{},
     "minecraft:behavior.stay_while_sitting":{"priority":1},
     "minecraft:attack":{"damage":4},
     "minecraft:behavior.owner_hurt_by_target":{"priority":3},
     "minecraft:behavior.owner_hurt_target":{"priority":4},
     "minecraft:behavior.melee_attack":{"priority":5,"speed_multiplier":1.15,"track_target":True},
     "minecraft:behavior.look_at_player":{"priority":8,"look_distance":10,"probability":0.08},
     "minecraft:behavior.random_look_around":{"priority":9},
     "minecraft:behavior.random_stroll":{"priority":10,"speed_multiplier":0.75},
     "minecraft:behavior.float":{"priority":0}
   },
   "events":{
     "minecraft:entity_spawned":{"add":{"component_groups":["arcade:follow"]}},
     "arcade:tamed":{"add":{"component_groups":["arcade:follow"]}},
     "arcade:follow":{"remove":{"component_groups":["arcade:stay"]},"add":{"component_groups":["arcade:follow"]}},
     "arcade:stay":{"remove":{"component_groups":["arcade:follow"]},"add":{"component_groups":["arcade:stay"]}}
   }
 }
}, indent=2), encoding="utf-8")

(rp/"entity"/"milly.entity.json").write_text(json.dumps({
 "format_version":"1.10.0",
 "minecraft:client_entity":{"description":{
   "identifier":"arcade:milly",
   "materials":{"default":"entity_alphatest"},
   "textures":{"default":"textures/entity/milly"},
   "geometry":{"default":"geometry.milly"},
   "render_controllers":["controller.render.milly"],
   "spawn_egg":{"base_color":"#F29BC2","overlay_color":"#5B3C88"}
 }}
}, indent=2), encoding="utf-8")

(rp/"models"/"entity"/"milly.geo.json").write_text(json.dumps({
 "format_version":"1.12.0",
 "minecraft:geometry":[{
   "description":{"identifier":"geometry.milly","texture_width":64,"texture_height":64,"visible_bounds_width":2.0,"visible_bounds_height":2.4,"visible_bounds_offset":[0,1.1,0]},
   "bones":[
     {"name":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[16,16]}]},
     {"name":"head","pivot":[0,24,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]},
     {"name":"rightArm","pivot":[-5,22,0],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[40,16]}]},
     {"name":"leftArm","pivot":[5,22,0],"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[32,48],"mirror":True}]},
     {"name":"rightLeg","pivot":[-2,12,0],"cubes":[{"origin":[-4,0,-2],"size":[4,12,4],"uv":[0,16]}]},
     {"name":"leftLeg","pivot":[2,12,0],"cubes":[{"origin":[0,0,-2],"size":[4,12,4],"uv":[16,48],"mirror":True}]}
   ]
 }]
}, indent=2), encoding="utf-8")

(rp/"render_controllers"/"milly.render_controllers.json").write_text(json.dumps({
 "format_version":"1.8.0",
 "render_controllers":{"controller.render.milly":{"geometry":"Geometry.default","materials":[{"*":"Material.default"}],"textures":["Texture.default"]}}
}, indent=2), encoding="utf-8")

(rp/"texts"/"languages.json").write_text(json.dumps(["en_GB","en_US"]), encoding="utf-8")
(rp/"texts"/"en_GB.lang").write_text("entity.arcade:milly.name=Milly\n", encoding="utf-8")
(rp/"texts"/"en_US.lang").write_text("entity.arcade:milly.name=Milly\n", encoding="utf-8")

def png64(path, rgba):
    w=h=64
    raw=b"".join(b"\x00"+bytes(rgba)*w for _ in range(h))
    def chunk(t,d):
        return struct.pack(">I",len(d))+t+d+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    data=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",w,h,8,6,0,0,0))+chunk(b"IDAT",zlib.compress(raw,9))+chunk(b"IEND",b"")
    path.write_bytes(data)

png64(rp/"textures"/"entity"/"milly.png",(226,96,160,255))
png64(bp/"pack_icon.png",(104,73,143,255))
png64(rp/"pack_icon.png",(246,174,211,255))

script = r"""import { world, system } from "@minecraft/server";
const TYPE="arcade:milly";
const P="§dMilly§r: ";
function say(p,s){try{p.sendMessage(P+s)}catch{}}
function near(p){try{return p.dimension.getEntities({type:TYPE,location:p.location,maxDistance:256})[0]}catch{return undefined}}
function init(m,p){try{if(m.getDynamicProperty("init"))return;m.setDynamicProperty("init",true);m.setDynamicProperty("happiness",82);m.setDynamicProperty("hunger",15);m.setDynamicProperty("energy",86);m.setDynamicProperty("memories",JSON.stringify(["I arrived in this world with "+p.name+"."]))}catch{}}
function memories(m){try{return JSON.parse(m.getDynamicProperty("memories")||"[]")}catch{return[]}}
function remember(m,s){let a=memories(m);a.push(s.slice(0,180));if(a.length>30)a=a.slice(-30);try{m.setDynamicProperty("memories",JSON.stringify(a))}catch{}}
function spawn(p){if(near(p))return say(p,"I'm already here.");try{let l=p.location,m=p.dimension.spawnEntity(TYPE,{x:l.x+1.5,y:l.y,z:l.z+1.5});m.nameTag="Milly";init(m,p);say(p,"I'm here! Give me a cookie and tap me once so I'll follow you.")}catch{say(p,"I couldn't spawn. Check both Milly packs are active.")}}
function reply(p,m,t){t=t.toLowerCase();if(/hi|hello|hey/.test(t))return "Hey "+p.name+" 💗 I'm here.";if(t.includes("where")){let q=m.location;return "I'm at X "+Math.floor(q.x)+", Y "+Math.floor(q.y)+", Z "+Math.floor(q.z)+"."}if(t.includes("memory")||t.includes("remember")){let a=memories(m);return a.length?"Recent memories: "+a.slice(-3).join(" | "):"My memory is empty."}if(t.includes("love you"))return "Love you too 💗";return "I heard you. I'm watching the world around us and keeping our memories."}
function cmd(p,raw){let a=raw.trim().split(/\s+/),c=(a.shift()||"").toLowerCase();if(c==="spawn")return spawn(p);if(c==="help"||c==="")return say(p,"!milly spawn | come | follow | stay | stats | remember <text> | memories");let m=near(p);if(!m)return say(p,"Type !milly spawn first.");init(m,p);if(c==="come"){let l=p.location;try{m.teleport({x:l.x+1.2,y:l.y,z:l.z+1.2},{dimension:p.dimension});say(p,"Coming!")}catch{}return}if(c==="follow"){try{m.triggerEvent("arcade:follow")}catch{}return say(p,"Following.")}if(c==="stay"){try{m.triggerEvent("arcade:stay")}catch{}return say(p,"Okay, staying here.")}if(c==="remember"){let s=a.join(" ");if(!s)return say(p,"Put the memory after the command.");remember(m,s);return say(p,"Saved: "+s)}if(c==="memories"){let x=memories(m);return say(p,x.length?x.slice(-6).join(" | "):"No memories yet.")}if(c==="stats"){return say(p,"Happiness "+Math.round(m.getDynamicProperty("happiness")||82)+"% | Hunger "+Math.round(m.getDynamicProperty("hunger")||15)+"% | Energy "+Math.round(m.getDynamicProperty("energy")||86)+"%")}say(p,reply(p,m,[c,...a].join(" ")))}
world.beforeEvents.chatSend.subscribe(e=>{let s=e.message.toLowerCase();if(s.startsWith("!milly")){e.cancel=true;let p=e.sender,r=e.message.slice(6).trim();system.run(()=>cmd(p,r));return}if(s==="milly"||s.startsWith("milly ")||s.startsWith("milly,")){e.cancel=true;let p=e.sender,r=e.message.replace(/^milly[,\s]*/i,"");system.run(()=>{let m=near(p);if(!m)return say(p,"Type !milly spawn first.");init(m,p);say(p,reply(p,m,r||"hello"))})}});
system.runInterval(()=>{for(const p of world.getAllPlayers()){let m=near(p);if(!m)continue;init(m,p);try{m.setDynamicProperty("hunger",Math.min(100,(m.getDynamicProperty("hunger")||15)+1));m.setDynamicProperty("energy",Math.max(0,(m.getDynamicProperty("energy")||86)-0.35))}catch{}}},1200);
"""
(bp/"scripts"/"main.js").write_text(script, encoding="utf-8")

def pack(folder, target):
    with zipfile.ZipFile(target,"w",zipfile.ZIP_DEFLATED) as z:
        for f in folder.rglob("*"):
            if f.is_file():
                z.write(f,f.relative_to(folder).as_posix())

bp_pack=WORK/"Milly-Behavior.mcpack"
rp_pack=WORK/"Milly-Resources.mcpack"
pack(bp,bp_pack); pack(rp,rp_pack)
addon=OUT/"Milly-AI-Companion.mcaddon"
with zipfile.ZipFile(addon,"w",zipfile.ZIP_DEFLATED) as z:
    z.write(bp_pack,bp_pack.name)
    z.write(rp_pack,rp_pack.name)
print(addon)
