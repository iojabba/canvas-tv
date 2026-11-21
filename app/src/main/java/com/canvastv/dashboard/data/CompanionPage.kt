package com.canvastv.dashboard.data

/** The phone page, served by [CompanionServer]. Plain HTML and JavaScript, no external files. */
object CompanionPage {
    private const val STYLE = """
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{--bg:#0f1720;--card:#18222e;--text:#eef2f6;--muted:#9fb0c2;--accent:#ffc857;--line:#2a3746;--bad:#ff8a80}
@media (prefers-color-scheme: light){:root{--bg:#f4f6f9;--card:#fff;--text:#111821;--muted:#566575;--accent:#9a6a00;--line:#dde3ea}button.primary,.tabs button.on{color:#fff!important}}
*{box-sizing:border-box}body{margin:0;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text)}
header{padding:16px;border-bottom:1px solid var(--line);display:flex;gap:12px;align-items:center;flex-wrap:wrap}
h1{font-size:20px;margin:0;flex:1}h2{font-size:17px;margin:20px 0 8px}main{padding:0 16px 40px;max-width:760px;margin:auto}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;margin:10px 0}
label{display:block;font-size:13px;color:var(--muted);margin-top:8px}
input,select,textarea{width:100%;font:inherit;padding:9px 10px;border-radius:9px;border:1px solid var(--line);background:var(--bg);color:var(--text)}
textarea{min-height:70px}button{font:inherit;padding:9px 14px;border-radius:9px;border:1px solid var(--line);background:var(--bg);color:var(--text);cursor:pointer}
button.primary{background:var(--accent);color:#111;border-color:var(--accent);font-weight:600}button.danger{color:var(--bad)}
.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}.muted{color:var(--muted);font-size:13px}.tag{font-size:12px;color:var(--muted)}
.tabs{display:flex;gap:6px;flex-wrap:wrap}.tabs button.on{background:var(--accent);color:#111;border-color:var(--accent)}
#toast{position:fixed;left:50%;bottom:20px;transform:translateX(-50%);background:#111c;color:#fff;padding:10px 16px;border-radius:10px;display:none}
code{font-size:12px;word-break:break-all}
</style>"""

    val PAIR = """<!doctype html><html><head><meta charset="utf-8"><title>Canvas TV</title>$STYLE</head><body>
<header><h1>Canvas TV</h1></header><main>
<div class="card"><h2>Pair this phone</h2>
<p class="muted">On the TV open <b>Settings → Phone setup</b> and type the 6-digit code shown there.</p>
<input id="code" inputmode="numeric" maxlength="6" placeholder="123456" autofocus>
<div class="row"><button class="primary" onclick="pair()">Pair</button></div><p id="err" class="muted"></p></div></main>
<script>
async function pair(){const r=await fetch('/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:document.getElementById('code').value})});
const j=await r.json();if(j.ok){location.reload()}else{document.getElementById('err').textContent=j.error}}
document.getElementById('code').addEventListener('keydown',e=>{if(e.key==='Enter')pair()});
</script></body></html>"""

    val APP = """<!doctype html><html><head><meta charset="utf-8"><title>Canvas TV</title>$STYLE</head><body>
<header><h1>Canvas TV</h1><button onclick="load()">Refresh</button></header>
<main>
<div class="card"><h2 style="margin-top:0">Send a message to the TV</h2>
<textarea id="msg" placeholder="Dinner's ready!"></textarea><label>From (optional)</label><input id="from" placeholder="Mom">
<div class="row"><button class="primary" onclick="sendMsg()">Send</button><button onclick="clearMsgs()">Clear the message board</button></div></div>

<div class="card"><h2 style="margin-top:0">Add a show</h2>
<p class="muted">Open the show on Netflix, YouTube, Disney+, Hulu, Prime Video, Max or another service in your phone's browser (or tap Share → Copy link in its app), then paste the address here. It's added to the page selected below, with the show's picture.</p>
<input id="showLink" placeholder="https://www.netflix.com/title/80117401">
<div class="row"><button class="primary" onclick="addShow()">Add the show</button></div></div>

<h2>Pages</h2><div class="tabs" id="tabs"></div>
<div id="tiles"></div>
<div class="card"><h2 style="margin-top:0">Add a tile to this page</h2>
<select id="newType"></select><div class="row"><button class="primary" onclick="addTile()">Add</button></div>
<p class="muted">It goes in the first free spot. Move and resize it with the remote in edit mode.</p></div>

<h2>Home Assistant</h2>
<div class="card"><p class="muted">For the Home Assistant tile. In Home Assistant open your profile → Security → Long-lived access tokens → Create token, then paste it here. The token stays on the TV and isn't shown again.</p>
<label>Server address</label><input id="haUrl" placeholder="http://192.168.1.20:8123">
<label>Long-lived access token <span id="haHas" class="tag"></span></label><input id="haToken" type="password" autocomplete="off" placeholder="Paste the token">
<div class="row"><button class="primary" onclick="saveHa()">Save</button></div></div>

<h2>Backup</h2>
<div class="card"><div class="row"><a href="/api/layout?download=1"><button>Download the layout</button></a>
<label style="margin:0"><input type="file" id="upload" accept=".json,application/json" style="display:none" onchange="restore(this.files[0])"><span class="tag"></span></label>
<button onclick="document.getElementById('upload').click()">Restore from a file…</button></div>
<p class="muted">Restoring keeps the current layout in the TV's version history.</p>
<details><summary class="muted">Edit the layout as JSON (advanced)</summary><textarea id="raw" style="min-height:300px;font-family:monospace;font-size:12px"></textarea>
<div class="row"><button onclick="saveRaw()">Save JSON</button></div></details></div>

<h2>Web commands</h2>
<div class="card"><p class="muted">For Home Assistant, Tasker, iOS Shortcuts or curl. Send the token below as <code>Authorization: Bearer …</code>.</p>
<p><code id="cmds"></code></p>
<div class="row"><button onclick="newToken()">Create a token for automations</button></div><p><code id="token"></code></p></div>
</main><div id="toast"></div>
<script>
const FIELDS={CLOCK:['style:analog or digital','24h:true/false','showDate:true/false','timezone:e.g. America/New_York','city'],
APP:['pkg:package name','label','display:art or text','showLabel:true/false'],
HDMI:['inputId','label','preview:true/false','cornerUrl:video address for game mode','cornerClock:true/false','cornerPos:BR, BL, TR or TL','cornerSize:S, M or L','cornerMuted:true/false'],
NOTE:['text*','size:S, M, L or XL'],VIDEO:['url','title','muted:true/false'],
COUNTDOWN:['label','date:YYYY-MM-DD','time:HH:MM (optional)','yearly:true/false'],FOLDER:['name','pkgs:package names, comma separated'],
WEATHER:['place:City, State','units:F or C','refreshMin'],NEWS:['urls*:feed addresses, comma separated','title','style:list or ticker','refreshMin'],
TICKER:['symbols:AAPL, MSFT, BTC, ETH','refreshMin'],CALENDAR:['icsUrls*:private iCal addresses, comma separated','title','days','refreshMin'],
PHOTOS:['sources*:picture or feed addresses, comma separated','seconds','shuffle:true/false','fit:fill or fit'],
WEB:['url','title','zoom:percent','refreshMin','js:true/false'],MESSAGES:['title'],APP_DRAWER:['label'],
HOME_ASSISTANT:['entities*:light.kitchen, scene.movie_time','title','refreshSec'],
SHOW:['link*:the show\'s web address','title:taken from the page if blank','image:picture address, taken from the page if blank','showTitle:true/false','pkg:app package, automatic if blank']};
const LABELS={CLOCK:'Clock',APP:'App shortcut',HDMI:'HDMI input',STATS:'System stats',NOTE:'Note',VIDEO:'Video stream',NOW_PLAYING:'Now playing',
COUNTDOWN:'Countdown',APP_DRAWER:'All apps',FOLDER:'App folder',WEATHER:'Weather',NEWS:'News / RSS',TICKER:'Stocks & crypto',CALENDAR:'Calendar',
PHOTOS:'Photo slideshow',WEB:'Web page',SPEEDTEST:'Speed test',MESSAGES:'Message board',HOME_ASSISTANT:'Home Assistant',SHOW:'TV show'};
let layout=null,page=0,apps=[];
const el=id=>document.getElementById(id);
function toast(t){const e=el('toast');e.textContent=t;e.style.display='block';clearTimeout(e._t);e._t=setTimeout(()=>e.style.display='none',2500)}
async function api(path,opt){const r=await fetch(path,opt);const j=await r.json().catch(()=>({ok:false,error:'Bad reply'}));if(j.ok===false)throw new Error(j.error);return j}
function esc(s){return String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
async function load(){
  layout=await api('/api/layout');apps=await api('/api/apps').catch(()=>[]);
  el('raw').value=JSON.stringify(layout,null,2);
  el('tabs').innerHTML=layout.pages.map((p,i)=>'<button class="'+(i==page?'on':'')+'" onclick="page='+i+';render()">'+esc(p.name)+'</button>').join('')
    +'<button onclick="showOnTv()">Show this page on the TV</button>';
  el('newType').innerHTML=Object.keys(LABELS).map(k=>'<option value="'+k+'">'+LABELS[k]+'</option>').join('');
  render();
}
function appName(pkg){const a=apps.find(a=>a.pkg==pkg);return a?a.label:pkg}
function render(){
  if(page>=layout.pages.length)page=0;
  [...el('tabs').children].forEach((b,i)=>b.className=i==page?'on':'');
  const tiles=[...layout.pages[page].tiles].sort((a,b)=>a.y-b.y||a.x-b.x);
  el('tiles').innerHTML=tiles.map(t=>{
    const f=(FIELDS[t.type]||[]).map(spec=>{const [k0,hint]=spec.split(':');const big=k0.endsWith('*');const k=k0.replace('*','');
      const v=esc((t.config||{})[k]);const id='f_'+t.id+'_'+k;
      const inp=big?'<textarea id="'+id+'">'+v+'</textarea>':'<input id="'+id+'" value="'+v+'"'+(hint?' placeholder="'+esc(hint)+'"':'')+'>';
      return '<label>'+k+'</label>'+inp}).join('');
    const name=t.type=='APP'?appName((t.config||{}).pkg):(t.config||{}).label||(t.config||{}).title||(t.config||{}).name||'';
    return '<div class="card"><b>'+LABELS[t.type]+'</b> <span class="tag">'+esc(name)+' · '+t.w+'×'+t.h+' at '+(t.x+1)+','+(t.y+1)+'</span>'+f
      +'<div class="row">'+(f?'<button class="primary" onclick="saveTile(\''+t.id+'\')">Save</button>':'')
      +'<button class="danger" onclick="delTile(\''+t.id+'\')">Delete</button></div></div>'}).join('')||'<p class="muted">This page has no tiles.</p>';
}
async function saveTile(id){const t=layout.pages[page].tiles.find(t=>t.id==id);const cfg={};
  (FIELDS[t.type]||[]).forEach(spec=>{const k=spec.split(':')[0].replace('*','');const e=el('f_'+id+'_'+k);if(e)cfg[k]=e.value.trim()});
  try{await api('/api/tile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({page:page,id:id,config:cfg})});toast('Saved');load()}catch(e){toast(e.message)}}
async function delTile(id){if(!confirm('Delete this tile?'))return;
  try{await api('/api/tile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({page:page,id:id,action:'delete'})});toast('Deleted');load()}catch(e){toast(e.message)}}
async function addTile(){try{await api('/api/tile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({page:page,action:'add',type:el('newType').value,config:{}})});toast('Added');load()}catch(e){toast(e.message)}}
async function addShow(){const l=el('showLink').value.trim();if(!l)return;
  try{await api('/api/tile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({page:page,action:'add',type:'SHOW',config:{link:l}})});el('showLink').value='';toast('Show added');load()}catch(e){toast(e.message)}}
async function showOnTv(){try{await api('/api/page',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({page:page+1})});toast('Showing on the TV')}catch(e){toast(e.message)}}
async function sendMsg(){const t=el('msg').value.trim();if(!t)return;
  try{await api('/api/message',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:t,from:el('from').value.trim()})});el('msg').value='';toast('Sent to the TV')}catch(e){toast(e.message)}}
async function clearMsgs(){try{await api('/api/message',{method:'DELETE'});toast('Cleared')}catch(e){toast(e.message)}}
async function restore(file){if(!file)return;const text=await file.text();
  try{await api('/api/layout',{method:'PUT',body:text});toast('Layout restored');load()}catch(e){toast(e.message)}}
async function saveRaw(){try{JSON.parse(el('raw').value)}catch(e){toast('That isn\'t valid JSON');return}
  try{await api('/api/layout',{method:'PUT',body:el('raw').value});toast('Saved');load()}catch(e){toast(e.message)}}
async function loadHa(){try{const h=await api('/api/homeassistant');el('haUrl').value=h.url||'';el('haHas').textContent=h.hasToken?'(one is saved; leave blank to keep it)':''}catch(e){}}
async function saveHa(){try{await api('/api/homeassistant',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:el('haUrl').value.trim(),token:el('haToken').value.trim()})});
  el('haToken').value='';toast('Saved on the TV');loadHa()}catch(e){toast(e.message)}}
async function newToken(){try{const j=await api('/api/token',{method:'POST'});el('token').textContent=j.token}catch(e){toast(e.message)}}
el('cmds').innerHTML='Message: curl -X POST '+location.origin+'/api/message -H "Authorization: Bearer TOKEN" -d \'{"text":"Doorbell"}\'<br><br>'
 +'Change page: curl -X POST '+location.origin+'/api/page -H "Authorization: Bearer TOKEN" -d \'{"page":2}\'<br><br>'
 +'Replace TOKEN with one made by the button below. Each token keeps working until you choose "Forget paired phones" on the TV.';
load().catch(e=>toast(e.message));loadHa();
</script></body></html>"""
}
