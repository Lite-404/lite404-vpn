'use strict';
window.LiteMotion = (() => {
 const clamp=(x,a,b)=>Math.max(a,Math.min(b,x)), mix=(a,b,t)=>a+(b-a)*t;
 class Spring {
  constructor(value,frequency=25,damping=.7){Object.assign(this,{value,velocity:0,frequency,damping});}
  at(target){return Math.abs(this.value-target)<.001&&Math.abs(this.velocity)<.015;}
  snap(target){this.value=target;this.velocity=0;}
  step(target,dt,frequency=this.frequency,critical=false){
   const x=this.value-target,v=this.velocity;
   if(critical){const p=v+frequency*x,e=Math.exp(-frequency*dt);this.value=target+e*(x+p*dt);this.velocity=e*(v-frequency*p*dt);}
   else {const decay=frequency*this.damping,w=frequency*Math.sqrt(1-this.damping**2),p=(v+decay*x)/w,c=Math.cos(w*dt),s=Math.sin(w*dt),e=Math.exp(-decay*dt),o=x*c+p*s;this.value=target+e*o;this.velocity=e*(w*(p*c-x*s)-decay*o);}
   if(this.at(target))this.snap(target);
  }
 }
 class DockDynamics {
  constructor(start){this.c=new Spring(start,27,.70);this.w=new Spring(.98,24,.57);this.h=new Spring(.95,22,.53);this.m=new Spring(0,26,.78);this.sh=new Spring(0,25,.70);this.l=new Spring(0,25,.76);this.activation=0;this.tap=0;this.hold=0;this.dest=start;this.frequency=19;this.dragging=false;this.swiping=false;this.finishing=false;this.repeat=false;}
  lift(i){return i.held&&(i.sameTab||i.dragging||this.hold>=.17)?1:0;}
  contact(i){return i.swiping?.74:this.lift(i)?1:i.held||this.tap>0?this.repeat?.48:.74:0;}
  strain(i){const f=i.target%1;return i.swiping?4*f*(1-f):0;}
  width(strain){return .98+clamp(this.m.value,0,1)*mix(this.repeat?-.068:.13-.06*strain,.49-.34*strain,clamp(this.l.value,0,1));}
  height(strain){return .95+clamp(this.m.value,0,1)*mix(this.repeat?-.075:.015-.18*strain,.43-.58*strain,clamp(this.l.value,0,1));}
  step(i,dt){
   const fresh=i.activation!==this.activation,swipePull=i.swiping?Math.tanh((i.target-this.dest)*12):0;
   if(fresh){this.activation=i.activation;this.repeat=i.sameTab;this.tap=this.repeat?.105:.15;this.hold=0;}
   if(i.swiping){this.tap=0;this.repeat=false;this.l.snap(0);}
   else if(this.swiping&&!i.held&&!fresh){this.finishing=true;this.frequency=19;this.c.snap(i.target);[this.m,this.w,this.h,this.sh].forEach(s=>s.snap(s.value));}
   else if(i.dragging||this.dragging)this.frequency=27;
   else if(i.target!==this.dest){const extra=clamp(Math.abs(i.target-this.c.value)-1,0,2);this.frequency=19/(1+.34*extra);this.tap=Math.max(this.tap,.16+.042*extra);this.repeat=false;}
   if(i.swiping||fresh||i.held||(!this.swiping&&i.target!==this.dest))this.finishing=false;
   this.dest=i.target;this.dragging=i.dragging;this.swiping=i.swiping;this.hold=i.held?this.hold+dt:0;
   this.m.step(this.contact(i),dt,26,this.finishing);this.tap=Math.max(0,this.tap-dt);this.l.step(this.lift(i),dt);
   if(i.swiping)this.c.snap(i.target);else this.c.step(i.target,dt,this.frequency);
   const pull=i.swiping?swipePull:Math.tanh((i.target-this.c.value)*4.8),strain=i.swiping?this.strain(i):pull*pull;
   this.w.step(this.width(strain),dt,24,this.finishing);this.h.step(this.height(strain),dt,22,this.finishing);this.sh.step(pull*clamp(this.m.value,0,1),dt,25,this.finishing);
  }
  moving(i){return i.activation!==this.activation||this.tap>0||i.swiping!==this.swiping||(i.held&&this.hold<.17)||!this.c.at(i.target)||!this.m.at(this.contact(i))||!this.l.at(this.lift(i))||!this.w.at(this.width(this.strain(i)))||!this.h.at(this.height(this.strain(i)))||!this.sh.at(0);}
 }
 const easeAbove=(x,limit,cushion)=>x>=limit+cushion?x:limit+cushion*Math.exp((x-limit-cushion)/cushion),easeBelow=(x,limit,cushion)=>-easeAbove(-x,-limit,cushion);
 function rail(el,initial,onSelect,{fill=false,vertical=false}={}){
  const buttons=[...el.querySelectorAll(':scope > button')],n=buttons.length,lens=document.createElement('div');lens.className='lens';el.prepend(lens);el.style.setProperty('--slots',n);
  let selected=initial,frame=0,last=0,down=null,dynamics=new DockDynamics(initial),input={target:initial,activation:0,held:false,dragging:false,sameTab:false,swiping:false};
  const light=()=>document.documentElement.classList.contains('light');
  const paint=()=>{
   const rect=el.getBoundingClientRect(),length=vertical?rect.height:rect.width,slot=length/n,H=vertical?rect.width:rect.height,m=light()?0:clamp(dynamics.m.value,0,1),lift=light()?0:clamp(dynamics.l.value,0,1),pos=light()?selected:dynamics.c.value;
   const edge=H*.02-8*lift,min=edge+slot*.39,max=length-min;
   const center=easeBelow(easeAbove((pos+.5)*slot,min,slot*.11),max,slot*.11),available=2*Math.min(center-edge,length-edge-center);
   let width=easeBelow(slot*(light()?.98:dynamics.w.value),available,slot*.09),height=H*(light()?.95:dynamics.h.value);
   let left=center-width/2;
   if(fill){left=mix(selected*slot,left,m);width=mix(slot,width,m);height=mix(H,height,m);}
   if(vertical){height=Math.min(height,H*(1+.10*lift));lens.style.left='50%';lens.style.top='0';lens.style.width=height+'px';lens.style.height=width+'px';lens.style.transform=`translate(-50%,${left}px) skewY(${light()?0:clamp(dynamics.sh.value,-1,1)*3}deg)`;}else{lens.style.width=width+'px';lens.style.height=height+'px';lens.style.transform=`translate(${left}px,-50%) skewX(${light()?0:clamp(dynamics.sh.value,-1,1)*3}deg)`;}lens.style.setProperty('--glass',m);
  };
  const tick=t=>{frame=0;if(!el.isConnected)return;const dt=last?Math.min((t-last)/1000,.032):1/60;last=t;dynamics.step(input,dt);paint();if(!light()&&dynamics.moving(input))frame=requestAnimationFrame(tick);else last=0;};
  const wake=()=>{if(!frame)frame=requestAnimationFrame(tick);};
  const activate=(index,notify=true)=>{const changed=index!==selected;selected=index;buttons.forEach((b,i)=>{b.classList.toggle('active',i===index);b.setAttribute('aria-selected',String(i===index));});input.target=index;input.swiping=false;wake();if(notify)onSelect(index,changed);};
  const target=e=>{const r=el.getBoundingClientRect(),raw=(vertical?e.clientY-r.top:e.clientX-r.left)/((vertical?r.height:r.width)/n)-.5,inner=clamp(raw,0,n-1);return inner+.24*Math.tanh((raw-inner)/.24);};
  el.addEventListener('pointerdown',e=>{if(e.button!==0||e.target.closest('button')?.disabled)return;el.setPointerCapture(e.pointerId);down={x:e.clientX,y:e.clientY,id:e.pointerId};input.activation++;input.held=true;input.sameTab=Math.round(clamp(target(e),0,n-1))===selected;input.target=target(e);input.dragging=false;wake();e.preventDefault();});
  el.addEventListener('pointermove',e=>{if(!down||down.id!==e.pointerId)return;if(Math.abs(vertical?e.clientY-down.y:e.clientX-down.x)>5)input.dragging=true;if(input.dragging)input.target=target(e);wake();});
  const finish=(e,cancel=false)=>{if(!down||down.id!==e.pointerId)return;const r=el.getBoundingClientRect(),inside=vertical?e.clientX>=r.left-24&&e.clientX<=r.right+24:e.clientY>=r.top-24&&e.clientY<=r.bottom+24;const index=inside&&!cancel?Math.round(clamp(target(e),0,n-1)):selected;down=null;input.held=false;input.dragging=false;if(el.hasPointerCapture(e.pointerId))el.releasePointerCapture(e.pointerId);activate(index,!cancel&&inside);};
  el.addEventListener('pointerup',e=>finish(e));el.addEventListener('pointercancel',e=>finish(e,true));el.addEventListener('lostpointercapture',()=>{if(down){down=null;input.held=false;input.dragging=false;input.target=selected;wake();}});
  buttons.forEach((b,i)=>{b.addEventListener('click',e=>{if(e.detail===0)activate(i);});b.addEventListener('keydown',e=>{if(e.key===(vertical?'ArrowDown':'ArrowRight')||e.key===(vertical?'ArrowUp':'ArrowLeft')){e.preventDefault();const next=clamp(i+(e.key===(vertical?'ArrowDown':'ArrowRight')?1:-1),0,n-1);buttons[next].focus();activate(next);}});});
  const observer=new ResizeObserver(()=>{if(el.isConnected)paint();else observer.disconnect();});observer.observe(el);activate(initial,false);paint();
  return {select:i=>activate(i,false),refresh:paint,swipe:p=>{input.swiping=true;input.target=p;wake();},finish:i=>{selected=i;input.target=i;input.swiping=false;activate(i,false);},destroy:()=>{observer.disconnect();cancelAnimationFrame(frame);}};
 }
 function toggle(el,initial,onChange){
  let value=initial,down=null,target=value?21:0,frame=0,last=0;const spring=new Spring(target,26,.72),thumb=el.querySelector('.thumb');
  const paint=()=>thumb.style.transform=`translateX(${spring.value}px) scale(${el.classList.contains('held')&&!document.documentElement.classList.contains('light')?1.38:1})`;
  const tick=t=>{frame=0;if(!el.isConnected)return;spring.step(target,last?Math.min((t-last)/1000,.032):1/60);last=t;paint();if(!spring.at(target))frame=requestAnimationFrame(tick);else last=0;};
  const wake=()=>{if(document.documentElement.classList.contains('light')){spring.snap(target);paint();return;}if(!frame)frame=requestAnimationFrame(tick);};
  const set=v=>{value=v;target=v?21:0;el.setAttribute('aria-checked',String(v));wake();};
  el.addEventListener('pointerdown',e=>{if(e.button!==0)return;e.preventDefault();el.setPointerCapture(e.pointerId);down={x:e.clientX,id:e.pointerId,drag:false};el.classList.add('held');paint();});
  el.addEventListener('pointermove',e=>{if(!down||e.pointerId!==down.id)return;down.drag ||= Math.abs(e.clientX-down.x)>4;if(down.drag){const r=el.getBoundingClientRect(),raw=e.clientX-r.left-16;target=clamp(raw,0,21)+4*Math.tanh((raw-clamp(raw,0,21))/14);wake();}});
  const finish=(e,cancel=false)=>{if(!down||e.pointerId!==down.id)return;const next=cancel?value:down.drag?target>=10.5:!value;down=null;el.classList.remove('held');set(next);if(el.hasPointerCapture(e.pointerId))el.releasePointerCapture(e.pointerId);if(!cancel)onChange(next);};
  el.addEventListener('pointerup',e=>finish(e));el.addEventListener('pointercancel',e=>finish(e,true));el.addEventListener('click',e=>{if(e.detail===0){set(!value);onChange(value);}});set(initial);return {set};
 }
 function slider(el,value,min,max,onInput,onCommit){
  let drag=null;const knob=el.querySelector('.knob'),fill=el.querySelector('.fill');
  const set=v=>{value=clamp(Math.round(v),min,max);const p=(value-min)/(max-min)*100;knob.style.left=p+'%';fill.style.width=p+'%';el.setAttribute('aria-valuenow',value);onInput(value);};
  const position=e=>{const r=el.getBoundingClientRect();set(min+clamp((e.clientX-r.left)/r.width,0,1)*(max-min));};
  el.addEventListener('pointerdown',e=>{if(e.button!==0)return;e.preventDefault();el.setPointerCapture(e.pointerId);drag=e.pointerId;el.classList.add('held');position(e);});
  el.addEventListener('pointermove',e=>{if(drag===e.pointerId)position(e);});
  const finish=e=>{if(drag!==e.pointerId)return;drag=null;el.classList.remove('held');if(el.hasPointerCapture(e.pointerId))el.releasePointerCapture(e.pointerId);onCommit(value);};
  el.addEventListener('pointerup',finish);el.addEventListener('pointercancel',finish);el.addEventListener('keydown',e=>{if(['ArrowLeft','ArrowDown','ArrowRight','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();set(e.key==='Home'?min:e.key==='End'?max:value+(['ArrowRight','ArrowUp'].includes(e.key)?1:-1));onCommit(value);}});set(value);
 }
 let pointer=null,glowFrame=0;
 const glow=()=>{glowFrame=0;if(!pointer||document.documentElement.classList.contains('light'))return;document.querySelectorAll('.glass').forEach(el=>{const r=el.getBoundingClientRect();if(r.bottom<-200||r.top>innerHeight+200)return;el.style.setProperty('--gx',(pointer.x-r.left)+'px');el.style.setProperty('--gy',(pointer.y-r.top)+'px');});document.documentElement.style.setProperty('--light',1);};
 document.addEventListener('pointermove',e=>{pointer={x:e.clientX,y:e.clientY};if(!glowFrame)glowFrame=requestAnimationFrame(glow);},{passive:true});document.addEventListener('pointerdown',e=>{pointer={x:e.clientX,y:e.clientY};glow();},{passive:true});document.addEventListener('pointerleave',()=>document.documentElement.style.setProperty('--light',0));document.addEventListener('pointerup',e=>{if(e.pointerType!=='mouse')document.documentElement.style.setProperty('--light',0);},{passive:true});
 function icon(svg,kind){
  if(!svg||document.documentElement.classList.contains('light'))return;
  svg.getAnimations().forEach(a=>a.cancel());
  svg.animate([{transform:'scale(1)',offset:0},{transform:'scale(.92)',offset:.05},{transform:'scale(.035)',offset:.13},{transform:'scale(1.12)',offset:.34},{transform:'scale(.98)',offset:.48},{transform:'scale(1)',offset:1}],{duration:1370,easing:'cubic-bezier(.22,1,.36,1)'});
  const shackle=svg.querySelector('.shackle');if(shackle){shackle.getAnimations().forEach(a=>a.cancel());shackle.style.transformOrigin='86px 59px';shackle.animate([{transform:'rotate(0deg)',offset:0},{transform:'rotate(0deg)',offset:.13},{transform:'rotate(34deg)',offset:.29},{transform:'rotate(23deg)',offset:.43},{transform:'rotate(25deg)',offset:.59},{transform:'rotate(-3deg)',offset:.78},{transform:'rotate(0deg)',offset:1}],{duration:1370,easing:'cubic-bezier(.22,1,.36,1)'});}
  if(kind==='gear')svg.animate([{rotate:'0deg'},{rotate:'-17deg',offset:.2},{rotate:'96deg',offset:.46},{rotate:'88deg',offset:.62},{rotate:'90deg'}],{duration:980,easing:'cubic-bezier(.4,0,.2,1)',fill:'forwards'});
 }
 return {rail,toggle,slider,icon,Spring};
})();
