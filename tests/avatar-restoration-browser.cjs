/** Real recovered RC5 PMX/textures and real Three.js renderer; no AI responses or audio fixtures.
 * Private model assets must be restored locally first. Native bridge supplies read-only UI defaults.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader','--disable-dev-shm-usage']});
 const record={scope:'Real recovered RC5 PMX, real textures, actual Three.js renderer and mobile controls in Chromium software WebGL. Native Android voice/provider are not tested.',checks:[],errors:[]};
 const check=(name,pass,detail)=>{record.checks.push({name,passed:!!pass,...(detail?{detail}:{})});console.log((pass?'PASS ':'FAIL ')+name);assert.ok(pass,name);};
 fs.mkdirSync('artifacts/rc5-restoration',{recursive:true});
 try {
  if (process.env.MYRAA_FULL_AVATAR_QA === '1') {
  const qa=await browser.newPage({viewport:{width:960,height:800}});
  qa.on('pageerror',e=>record.errors.push(e.message));
  await qa.goto('http://127.0.0.1:5189/tests/renderer.html');
  await qa.waitForFunction(()=>typeof window.runAvatarQA==='function');
  const result=await qa.evaluate(()=>window.runAvatarQA('evelyn'));
  record.characterRuntime=result;
  const model=result.results.find(r=>r.id==='evelyn');
  check('the real Evelyn PMX loads without a runtime error',!model.error,model.error);
  for(const [name,value] of Object.entries(model.checks||{}))check('character runtime: '+name,value.status==='pass',value.detail);
  check('wave geometry stays finite',model.wave?.finite===true);
  check('wave moves the hand',model.wave?.handTravel>0.01,model.wave);
  await qa.screenshot({path:'artifacts/rc5-restoration/character-runtime.png'});
  await qa.close();
  }
  const page=await browser.newPage({viewport:{width:430,height:932},deviceScaleFactor:1});
  page.on('pageerror',e=>record.errors.push(e.message));
  const failed=[];page.on('response',r=>{if(r.status()>=400&&r.url().includes('/assets/characters/'))failed.push(r.url());});
  await page.addInitScript(()=>{
   window.MyraaNative={getState:()=>JSON.stringify({history:[],keyConfigured:false,spokenReplies:false,characterAvailable:true,quality:'low',language:'en-IN',model:'gemini-3.5-flash',liveModel:'gemini-3.1-flash-live-preview'}),getScreenState:()=>JSON.stringify({state:'IDLE',hasProjection:false}),endLive:()=>{},cancel:()=>{},stopSpeech:()=>{}};
  });
  await page.goto('http://127.0.0.1:5189/mobile.html');
  await page.waitForFunction(()=>window.__mobileDiagnostics?.()?.loaded===true,{},{timeout:90000});
  await page.waitForTimeout(1000);
  let diag=await page.evaluate(()=>window.__mobileDiagnostics());
  check('mobile home loads the actual avatar and skeleton',diag.bones>100,diag);
  check('avatar physics are active',diag.physicsNodes>0);
  check('avatar draws real geometry',diag.drawCalls>0);
  check('all recovered texture/model requests succeed',failed.length===0,failed);
  check('placeholder is absent',await page.locator('#avatar.neutral-avatar').count()===0);
  await page.screenshot({path:'artifacts/rc5-restoration/myraa-home-restored.png'});
  await page.locator('#wave').click();
  await page.waitForFunction(()=>window.__mobileDiagnostics().behaviour==='wave');
  check('mobile Wave control activates real animation',true);
  await page.waitForTimeout(8000);
  await page.screenshot({path:'artifacts/rc5-restoration/myraa-wave-restored.png'});
  await page.locator('#sit').click();
  await page.waitForFunction(()=>window.__mobileDiagnostics().seat!==null);
  check('mobile Sit control activates a real seat pose',true);
  await page.waitForTimeout(15000);
  await page.screenshot({path:'artifacts/rc5-restoration/myraa-sit-restored.png'});
  await page.locator('#stand').click();
  await page.waitForFunction(()=>window.__mobileDiagnostics().seat===null);
  check('mobile Stand restores standing pose',true);
  await page.locator('#happy').click();
  await page.waitForFunction(()=>window.__mobileDiagnostics().expression==='happy');
  check('mobile Smile changes the real expression',true);
  await page.locator('#view').click();
  check('portrait/full-body control remains available',await page.locator('#view').textContent()==='Full body');
  check('RC8 Screen Companion remains mounted',await page.locator('#screen-companion-entry').count()===1);
  await page.locator('#screen-companion-entry').click();
  check('RC8 Screen Companion workspace opens',await page.locator('#screen-companion').evaluate(d=>d.open));
  await page.close();
  const geometryPage=await browser.newPage();
  await geometryPage.goto('http://127.0.0.1:5189/tests/renderer.html');
  const geometry=await geometryPage.evaluate(async()=>{const module=await import('/tests/avatar-restoration-geometry.ts');return module.runRestoredGeometryChecks();});
  record.geometry=geometry;
  for(const result of geometry.results)check(result.name,result.passed,result.detail);
  check('no uncaught renderer errors',record.errors.length===0,record.errors);
 } catch(e){record.errors.push(e.stack||String(e));process.exitCode=1;}
 finally{fs.writeFileSync('artifacts/rc5-restoration/avatar-browser-tests.json',JSON.stringify(record,null,2));await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
