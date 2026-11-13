/** Deterministic pose checks on the real restored PMX; only intermediate GPU draws are skipped. */
import * as THREE from 'three';
import { CharacterSystem } from '../src/character/core/CharacterSystem';
import { evelynConfig } from '../src/character/config/characters/evelyn';
import { qualityProfile, withQuality } from '../src/character/core/quality';
export async function runRestoredGeometryChecks() {
  const canvas=document.createElement('canvas');document.body.replaceChildren(canvas);
  const quality={...qualityProfile('low'),shadows:false,cloth:false,antialias:false,maxTextureSize:512};
  const system=new CharacterSystem({canvas,config:withQuality(evelynConfig,quality),quality});system.resize(320,560);
  const results:Array<{name:string;passed:boolean;detail?:unknown}>=[];
  const check=(name:string,passed:boolean,detail?:unknown)=>results.push({name,passed,detail});
  try {
    await system.load();system.stop();system.setIdleBehaviours(false);
    const update=(count:number)=>{const render=system.stage.render;system.stage.render=()=>{};try{system.stepFrames(count);}finally{system.stage.render=render;}system.stage.render();};
    const bone=(slot:string)=>{const name=system.rig?.humanoid[slot]?.bone;const index=name?system.loadedModel!.boneIndexByName.get(name):undefined;return index===undefined?null:system.loadedModel!.bones[index];};
    const position=(slot:string)=>bone(slot)!.getWorldPosition(new THREE.Vector3());
    update(30);
    const before=position('LEFT_HAND');system.setFrameInput({emotion:'happy'});system.playBehaviour('wave');update(60);const after=position('LEFT_HAND');
    check('real wave moves the wrist in world space',before.distanceTo(after)>0.05,{before:before.toArray(),after:after.toArray()});
    system.stopBehaviour();system.stand(0);update(30);const plan=system.sit({seat:'chair',style:'upright',duration:0});update(120);
    const legs=['LEFT','RIGHT'].map(side=>{const hip=position(side+'_UPPER_LEG'),knee=position(side+'_LOWER_LEG'),foot=position(side+'_FOOT');return {forward:knee.clone().sub(hip).normalize().dot(new THREE.Vector3(0,0,1)),footBelowKnee:foot.y<knee.y};});
    check('actual sitting bends both thighs forward',!!plan&&legs.every(leg=>leg.forward>0.6),legs);
    check('seated feet remain below the knees',legs.every(leg=>leg.footBelowKnee),legs);
    system.stand(0);update(120);
    const hip=position('LEFT_UPPER_LEG'),knee=position('LEFT_LOWER_LEG');
    check('standing restores a vertical upper leg',Math.abs(knee.clone().sub(hip).normalize().y)>0.9);
    check('actual skeleton remains finite',system.loadedModel!.bones.every(b=>[...b.position,...b.quaternion].every(Number.isFinite)));
    check('real secondary motion stays healthy',system.secondaryMotion?.healthy===true);
    return {scope:'Real PMX, rig, animation and physics updates. Intermediate GPU draws skipped, final poses rendered; no AI/provider simulation.',results};
  } finally {system.dispose();}
}
