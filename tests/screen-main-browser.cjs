/** Existing mobile entrypoint integration; native capture is a fixture, not Android evidence. */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:360,height:780}});const checks=[],errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 const check=(name,pass)=>{assert.ok(pass,name);checks.push(name);console.log('PASS '+name);};
 try{
  await page.addInitScript(()=>{
   window.screenCalls=[];
   window.MyraaNative={getState:()=>JSON.stringify({history:[],keyConfigured:false,spokenReplies:false,characterAvailable:false,quality:'low',language:'ur-PK',model:'fixture',liveModel:'fixture-live'}),getScreenState:()=>JSON.stringify({state:'IDLE',hasProjection:false,sessionId:'',frameSequence:0}),screenStart:mode=>screenCalls.push({name:'start',mode}),screenStop:()=>screenCalls.push({name:'stop'}),screenPause:paused=>screenCalls.push({name:'pause',paused}),screenReview:()=>screenCalls.push({name:'review'}),screenAnalyze:(...args)=>screenCalls.push({name:'analyze',args}),screenDiscardReview:()=>screenCalls.push({name:'discard'}),screenSetOptions:()=>{},cancel:()=>{},stopSpeech:()=>{},endLive:()=>{},speak:()=>{},chat:()=>{},configure:()=>{},checkConnection:()=>{},clearHistory:()=>{},clearKey:()=>{},openSettings:()=>{},openFloatingSettings:()=>{}};
  });
  await page.goto(process.env.MYRAA_TEST_URL||'http://127.0.0.1:5178/mobile.html');await page.waitForSelector('#screen-companion-entry');
  await page.locator('#screen-companion-entry').click();check('mobile entrypoint mounts honest idle Screen Companion',await page.locator('#screen-state').textContent()==='idle');
  check('workspace follows existing Urdu response preference',await page.locator('#screen-language').inputValue()==='ur');
  await page.locator('#screen-start').click();check('real mobile Start handler reaches native bridge once without fabricated capture',await page.evaluate(()=>screenCalls.filter(c=>c.name==='start').length===1&&document.querySelector('#screen-state').textContent==='idle'));
  await page.evaluate(()=>MyraaEvents({type:'screen-state',state:'CAPTURING',hasProjection:true,sessionId:'fixture-session',frameSequence:1}));
  check('main event router forwards native capture state',await page.locator('#screen-state').textContent()==='capturing');
  await page.evaluate(()=>MyraaEvents({type:'screen-open',feature:'translate',question:'Translate the visible heading',language:'hi'}));
  check('voice review prefill survives main event routing',await page.locator('[data-feature=translate]').getAttribute('aria-pressed')==='true'&&await page.locator('#screen-question').inputValue()==='Translate the visible heading'&&await page.locator('#screen-language').inputValue()==='hi');
  await page.evaluate(()=>MyraaEvents({type:'screen-state',state:'CAPTURING',hasProjection:false,sessionId:'fixture-session',frameSequence:1}));
  check('inconsistent active state cannot claim screen access without ownership',await page.locator('#screen-state').textContent()==='stopped'&&await page.locator('#screen-review').isDisabled());
  await page.locator('#screen-close').click();check('existing composer and settings remain mounted',await page.locator('#input').count()===1&&await page.locator('#settings').count()===1);
  check('mobile Screen Companion integration has no uncaught errors',errors.length===0);
  fs.mkdirSync('artifacts',{recursive:true});fs.writeFileSync('artifacts/rc8-screen-main-browser.json',JSON.stringify({scope:'Actual existing mobile entrypoint with simulated native bridge/state events; no Android capture or Gemini inference.',checks,errors},null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
