/** Browser UI/audio integration. Native bridge and Gemini replies are fixtures. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
(async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']});
  const page=await browser.newPage({viewport:{width:390,height:844},permissions:['microphone']});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const checks=[];const check=(label,ok)=>{assert.ok(ok,label);checks.push(label);console.log('PASS '+label);};
  try{
    await page.addInitScript(()=>{
      window.calls=[];
      window.fixtureState={history:[],keyConfigured:true,spokenReplies:false,characterAvailable:false,quality:'low',language:'en-IN',model:'fixture',liveModel:'fixture-live'};
      window.MyraaNative={
        getState:()=>JSON.stringify(window.fixtureState),
        chat:(text,id)=>{calls.push({type:'chat',text,id});setTimeout(()=>MyraaEvents({type:'reply',id,text:'Planning your task.'}),15);},
        mobileAction:(id,name,raw)=>{calls.push({type:'action',name,args:JSON.parse(raw)});setTimeout(()=>MyraaEvents({type:'mobile-action-result',id,result:{status:'opened',message:'Clock setup opened.'}}),10);},
        taskTool:(id,raw)=>{calls.push({type:'taskTool',args:JSON.parse(raw)});setTimeout(()=>MyraaEvents({type:'mobile-action-result',id,result:{status:'started',taskId:'voice-task',message:'Planning started.'}}),10);},
        setListening:enabled=>{calls.push({type:'wake',enabled});MyraaEvents({type:'wake-state',enabled,status:enabled?'listening':'stopped',message:enabled?'Say Hey MYRAA':'Listening off'});},
        continueTask:()=>calls.push({type:'continue'}),
        cancel:()=>{calls.push({type:'cancel'});MyraaEvents({type:'wake-state',enabled:false,status:'stopped',message:'Stopped.'});MyraaEvents({type:'mobile-task',task:{id:'fixture',goal:'Prepare my journey',status:'cancelled',message:'Stopped. Already opened screens remain under your control.',steps:[]}});},cancelMobileAction:id=>calls.push({type:'cancelAction',id}),
        stopSpeech:()=>{},speak:()=>{},configure:()=>{},checkConnection:()=>{},clearHistory:()=>{},clearKey:()=>{},openSettings:()=>{},openFloatingSettings:()=>{},
        startLive:id=>{calls.push({type:'startLive'});setTimeout(()=>MyraaEvents({type:'live-token',id,token:'auth_tokens/test-only',model:'fixture-live',voice:'Aoede',instruction:'Fixture',backgroundLive:true}),10);},
        endLive:()=>calls.push({type:'endLive'}),
      };
      window.WebSocket=class{
        static OPEN=1;readyState=0;
        constructor(){window.fixtureSocket=this;setTimeout(()=>{this.readyState=1;this.onopen?.({});},10);}
        send(raw){const d=JSON.parse(raw);if(d.setup){window.setup=d.setup;setTimeout(()=>this.onmessage?.({data:JSON.stringify({setupComplete:{}})}),5);}if(d.realtimeInput?.audio)window.pcmCount=(window.pcmCount||0)+1;if(d.toolResponse)calls.push({type:'toolResponse',response:d.toolResponse});}
        close(){this.readyState=3;}
      };
      const getMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=async(...args)=>{const stream=await getMedia(...args);window.fixtureStream=stream;return stream;};
    });
    await page.goto(process.env.MYRAA_TEST_URL||'http://127.0.0.1:5178/mobile.html');
    await page.waitForSelector('#wake');
    check('source build renders neutral avatar without character assets',await page.locator('#avatar').textContent()==='M');
    await page.locator('#input').fill('set a timer for 5 minutes');await page.locator('#send').click();
    await page.waitForFunction(()=>calls.some(c=>c.type==='action'));
    check('typed exact command uses native action route',await page.evaluate(()=>calls.some(c=>c.type==='action'&&c.args.seconds===300)));
    await page.locator('#input').fill('Open Chrome then prepare a calendar event');await page.locator('#send').click();await page.waitForFunction(()=>calls.some(c=>c.type==='chat'));
    check('compound request reaches shared native planner',await page.evaluate(()=>calls.some(c=>c.type==='chat'&&c.text.includes('calendar'))));
    await page.evaluate(()=>MyraaEvents({type:'mobile-task',task:{id:'fixture',goal:'Prepare my journey',status:'waiting_user',message:'Calendar draft opened. Review and save it in Calendar.',next:1,steps:[{action:'calendar_event'},{action:'navigate'}],outcomes:[{step:0,status:'draft_opened'}]}}));
    check('task panel reports draft and exposes explicit continuation',await page.locator('#task-panel').isVisible()&&await page.locator('#task-continue').isVisible()&&(await page.locator('#task-status').textContent()).includes('draft opened'));
    await page.locator('#task-continue').click();check('Continue reaches native coordinator',await page.evaluate(()=>calls.some(c=>c.type==='continue')));
    await page.locator('#wake').click();check('wake listening is explicit and visible',await page.locator('#wake').getAttribute('aria-pressed')==='true');
    await page.waitForFunction(()=>document.querySelector('#presence').textContent!=='Thinking with you');
    await page.evaluate(()=>MyraaEvents({type:'wake-trigger'}));await page.waitForFunction(()=>window.pcmCount>0);
    check('wake activation starts real browser microphone PCM with fixture provider',await page.evaluate(()=>fixtureStream.getAudioTracks().some(t=>t.readyState==='live')));
    check('Live declares both action and task tools',await page.evaluate(()=>setup.tools[0].functionDeclarations.some(t=>t.name==='mobile_task')&&setup.tools[0].functionDeclarations.some(t=>t.name==='mobile_action')));
    await page.evaluate(()=>fixtureSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'task-one',name:'mobile_task',args:{operation:'start',goal:'Open Maps then set a timer'}}]}})}));
    await page.waitForFunction(()=>calls.some(c=>c.type==='toolResponse'));
    check('voice task call reaches native task bridge and returns correlated result',await page.evaluate(()=>calls.some(c=>c.type==='taskTool')&&calls.some(c=>c.type==='toolResponse'&&c.response.functionResponses[0].id==='task-one'&&c.response.functionResponses[0].response.status==='started')));
    await page.evaluate(()=>fixtureSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'task-one',name:'mobile_task',args:{operation:'start',goal:'duplicate'}}]}})}));
    await page.waitForTimeout(50);check('duplicate Live request does not start a second task',await page.evaluate(()=>calls.filter(c=>c.type==='taskTool').length===1));
    await page.evaluate(()=>fixtureSocket.onmessage({data:JSON.stringify({serverContent:{inputTranscription:{text:'Stop'}}})}));await page.waitForFunction(()=>fixtureStream.getTracks().every(t=>t.readyState==='ended'));
    check('spoken Stop releases microphone, closes socket, disables listening and cancels native work',await page.evaluate(()=>fixtureSocket.readyState===3&&calls.some(c=>c.type==='cancel')&&document.querySelector('#wake').getAttribute('aria-pressed')==='false'));
    check('no uncaught browser errors',errors.length===0);
    fs.mkdirSync('artifacts',{recursive:true});await page.screenshot({path:'artifacts/android-task-ui.png'});
    fs.writeFileSync('artifacts/mobile-tasks-browser.json',JSON.stringify({scope:'Real browser UI and shared microphone/audio engine; simulated Android bridge and Gemini socket. No cloud inference or Android recognition tested.',checks,errors},null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
