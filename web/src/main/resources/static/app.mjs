import { validateFacebookUrl, formatDuration, validateMetadata, safeApiPath } from './core.mjs';

const $ = (id) => document.getElementById(id);
const settings = window.CLIPPOCKET_CONFIG ?? {apiBase:'',previewOnly:true};
const API = typeof settings.apiBase === 'string' ? settings.apiBase.replace(/\/$/, '') : '';
const previewOnly = settings.previewOnly !== false;
const state = {metadata:null, selected:null, job:null, generation:0, controller:null, polling:null, demo:false};
let toastTimer;
function toast(message) { $('toast').textContent=message; $('toast').hidden=false; clearTimeout(toastTimer); toastTimer=setTimeout(()=>$('toast').hidden=true,4200); }
function inputError(message='') { $('input-error').textContent=message; $('input-error').hidden=!message; $('source-input').setAttribute('aria-invalid',String(!!message)); document.querySelector('.input-box').classList.toggle('invalid',!!message); }
function downloadStatus(message, error=false) { $('download-status').textContent=message; $('download-status').hidden=!message; $('download-status').classList.toggle('error',error); }
function step(number) { document.querySelectorAll('[data-step]').forEach(el=>{const n=Number(el.dataset.step);el.classList.toggle('current',n===number);el.classList.toggle('done',n<number);if(n===number)el.setAttribute('aria-current','step');else el.removeAttribute('aria-current');}); }
function busyAnalysis(busy) { $('analyze-button').disabled=busy; $('analyze-button').classList.toggle('is-loading',busy); $('analyze-label').textContent=busy?'正在解析影片…':'解析影片';$('cancel-analysis').hidden=!busy; }
function busyDownload(busy) { $('prepare-button').disabled=busy; $('quality-fieldset').disabled=busy; $('prepare-button').classList.toggle('is-loading',busy);$('prepare-label').textContent=busy?'正在準備影片…':'準備下載';$('transfer-panel').hidden=!busy; }

async function request(path, options={}, timeout=75000) {
  const controller=new AbortController();
  const signal=options.signal;
  const onAbort=()=>controller.abort();
  if(signal?.aborted) controller.abort(); else signal?.addEventListener('abort',onAbort,{once:true});
  const timer=setTimeout(()=>controller.abort(),timeout);
  try {
    const response=await fetch(API+path,{...options,credentials:'same-origin',headers:{'Accept':'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...options.headers},signal:controller.signal});
    if(!response.headers.get('content-type')?.includes('application/json')) throw new Error('下載服務尚未連線，請確認已啟動完整專案。');
    const data=await response.json();
    if(!response.ok) throw new Error(data.message || '下載服務暫時無法使用，請稍後再試。');
    return data;
  } catch(error) {
    if(error.name==='AbortError') { if(signal?.aborted) throw error; throw new Error('服務回應逾時，請稍後重試。'); }
    if(error instanceof TypeError) throw new Error('無法連接下載服務，請檢查網路與服務狀態。');
    throw error;
  } finally {clearTimeout(timer);signal?.removeEventListener('abort',onAbort);}
}

async function stopJob(silent=false) {
  const id=state.job; state.job=null; clearTimeout(state.polling);
  if(id && !state.demo) {try {await request('/api/jobs/'+encodeURIComponent(id),{method:'DELETE'},15000);}catch{if(!silent)toast('無法確認是否取消，伺服器會依逾時設定停止工作。');}}
}
function resetResult() {
  $('result-section').hidden=true;$('ready-panel').hidden=true;$('prepare-button').hidden=false;
  $('video-preview').pause();$('video-preview').removeAttribute('src');$('video-preview').load();$('video-preview').hidden=true;
  $('save-video').removeAttribute('href');$('preview-placeholder').hidden=false;$('preview-title').textContent='你的影片，就在這裡';$('preview-subtitle').textContent='選擇畫質，準備好後即可播放';
  busyDownload(false);downloadStatus('');step(1);
}
function presentMetadata(metadata,demo=false) {
  state.metadata=metadata;state.selected=metadata.formats[0].id;state.demo=demo;
  $('result-title').textContent=metadata.title || 'Facebook 影片';
  $('result-description').textContent=[metadata.uploader,formatDuration(metadata.duration)].filter(Boolean).join(' · ');
  $('result-badge').textContent=demo?'示範資料':'已取得影片資訊';
  $('quality-note').textContent=demo?'此為操作示範，畫質與內容為範例資料。':'僅列出影片實際提供的畫質';
  $('facebook-original').hidden=demo;
  if(!demo)$('facebook-original').href=validateFacebookUrl(metadata.sourceUrl);
  const container=$('source-options');container.replaceChildren();
  for(const format of metadata.formats) {
    const label=document.createElement('label');label.className='source-option';
    const radio=document.createElement('input');radio.type='radio';radio.name='quality';radio.value=format.id;radio.checked=format.id===state.selected;
    radio.addEventListener('change',()=>{state.selected=format.id;void stopJob(true);$('ready-panel').hidden=true;$('prepare-button').hidden=false;$('video-preview').pause();$('video-preview').removeAttribute('src');$('video-preview').load();$('video-preview').hidden=true;$('preview-placeholder').hidden=false;downloadStatus('');step(2);});
    const span=document.createElement('span');span.textContent=format.label;const small=document.createElement('small');small.textContent=format.detail || 'MP4';span.append(small);label.append(radio,span);container.append(label);
  }
  $('result-section').hidden=false;step(2);$('result-section').focus({preventScroll:true});$('result-section').scrollIntoView({behavior:'smooth',block:'start'});
}

async function analyze(raw) {
  const url=validateFacebookUrl(raw);
  if(previewOnly) throw new Error('目前為介面預覽，尚未連接下載服務。可點選「體驗操作流程」。');
  const generation=++state.generation;state.controller?.abort();state.controller=new AbortController();
  void stopJob(true);resetResult();inputError();busyAnalysis(true);
  try {
    const metadata=validateMetadata(await request('/api/videos/inspect',{method:'POST',body:JSON.stringify({url}),signal:state.controller.signal}));
    if(generation!==state.generation)return {cancelled:true};
    presentMetadata(metadata);return {id:metadata.id,title:metadata.title,formats:metadata.formats};
  } finally {if(generation===state.generation)busyAnalysis(false);}
}

$('source-form').addEventListener('submit',async event=>{event.preventDefault();try{await analyze($('source-input').value);}catch(error){if(error.name!=='AbortError')inputError(error.message);}});
$('cancel-analysis').addEventListener('click',()=>{++state.generation;state.controller?.abort();busyAnalysis(false);toast('已停止等待解析');});
$('source-input').addEventListener('input',()=>{inputError();$('clear-input').hidden=!$('source-input').value; if(state.controller){++state.generation;state.controller.abort();state.controller=null;busyAnalysis(false);}if(state.metadata){void stopJob(true);state.metadata=null;resetResult();}});
$('clear-input').addEventListener('click',()=>{$('source-input').value='';$('source-input').dispatchEvent(new Event('input'));$('source-input').focus();});
$('paste-button').addEventListener('click',async()=>{try{if(!navigator.clipboard?.readText)throw new Error();const text=await navigator.clipboard.readText();$('source-input').value=text.slice(0,2048);$('source-input').dispatchEvent(new Event('input'));if(text.length>2048)inputError('剪貼簿內容過長，請只複製影片連結。');else toast('已貼上連結');}catch{$('source-input').focus();toast('請長按輸入欄位，選擇「貼上」。');}});

async function pollJob(id,generation) {
  if(id!==state.job || generation!==state.generation)return;
  try {
    const data=await request('/api/jobs/'+encodeURIComponent(id),{},20000);
    if(id!==state.job || generation!==state.generation)return;
    if(data.status==='READY') {
      busyDownload(false);$('prepare-button').hidden=true;$('ready-panel').hidden=false;
      const fileUrl=safeApiPath(API,data.fileUrl);$('save-video').href=fileUrl;$('save-video').setAttribute('download','');
      $('video-preview').src=safeApiPath(API,data.previewUrl);$('video-preview').hidden=false;$('video-preview').load();$('preview-placeholder').hidden=true;
      downloadStatus('影片已準備好，可播放確認後儲存。');step(3);return;
    }
    if(['FAILED','CANCELLED'].includes(data.status)){busyDownload(false);downloadStatus(data.message || '影片準備失敗，請稍後再試。',true);return;}
    $('transfer-text').textContent=data.status==='QUEUED'?'已排入處理佇列…':'正在下載與整理影片…';
    state.polling=setTimeout(()=>void pollJob(id,generation),1600);
  } catch(error) {if(id!==state.job || generation!==state.generation)return;await stopJob(true);busyDownload(false);downloadStatus(error.message,true);}
}

$('prepare-button').addEventListener('click',async()=>{
  if(state.demo){downloadStatus('操作示範到這裡完成。啟動完整專案後，這裡會提供真實影片的預覽與儲存。');return;}
  if(!state.metadata)return;
  const generation=state.generation;busyDownload(true);downloadStatus('');$('download-progress').removeAttribute('value');
  try {
    const data=await request('/api/jobs',{method:'POST',body:JSON.stringify({videoId:state.metadata.id,formatId:state.selected})},20000);
    if(generation!==state.generation){if(data.id)await request('/api/jobs/'+encodeURIComponent(data.id),{method:'DELETE'});return;}
    if(typeof data.id!=='string')throw new Error('服務回傳的工作資訊不完整。');
    state.job=data.id;void pollJob(data.id,generation);
  }catch(error){if(generation!==state.generation)return;busyDownload(false);downloadStatus(error.message,true);}
});
$('cancel-download').addEventListener('click',async()=>{++state.generation;await stopJob();busyDownload(false);downloadStatus('已取消影片準備。');});
$('save-video').addEventListener('click',()=>downloadStatus('已交由瀏覽器儲存，請至「下載項目」確認檔案。'));
$('video-preview').addEventListener('error',()=>{if($('video-preview').getAttribute('src'))downloadStatus('此瀏覽器無法播放預覽，或暫存已過期。可嘗試儲存影片，必要時重新解析。',true);});

const dialog=$('help-dialog');document.querySelectorAll('.help-trigger').forEach(button=>button.addEventListener('click',()=>dialog.showModal()));
$('close-help').addEventListener('click',()=>dialog.close());dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();}});
if(previewOnly){$('preview-notice').hidden=false;$('service-badge').textContent='介面預覽';}
$('try-demo').addEventListener('click',()=>{resetResult();presentMetadata({id:'demo',title:'週末片刻，值得收藏。',uploader:'ClipPocket 示範影片',duration:42,formats:[{id:'hd',label:'1080p 高畫質',detail:'MP4 · 示例'},{id:'sd',label:'720p 標準畫質',detail:'MP4 · 示例'}]},true);});

if(document.modelContext?.registerTool) {
  const lifecycle=new AbortController();
  const tool={name:'inspect_facebook_video',title:'解析 Facebook 影片',description:'解析公開 Facebook 影片連結，更新頁面並回傳影片與畫質。此步驟不建立下載工作。',inputSchema:{type:'object',properties:{url:{type:'string'}},required:['url'],additionalProperties:false},annotations:{readOnlyHint:false,untrustedContentHint:true},async execute(input){try{if(!input || typeof input.url!=='string')throw new Error('請提供 url 字串。');$('source-input').value=input.url;$('clear-input').hidden=false;return await analyze(input.url);}catch(error){inputError(error.message);return {error:error.message};}}};
  try{Promise.resolve(document.modelContext.registerTool(tool,{signal:lifecycle.signal})).catch(()=>{});}catch{}
  window.addEventListener('pagehide',()=>lifecycle.abort(),{once:true});
}
window.addEventListener('pagehide',()=>{state.controller?.abort();clearTimeout(state.polling);});
