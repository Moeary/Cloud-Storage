const mineflayer = require('mineflayer');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const readline = require('node:readline');
const { setTimeout: sleep } = require('node:timers/promises');
const pending = new Map();
let sequence = 0;
readline.createInterface({input:process.stdin}).on('line', line => {
  const message=JSON.parse(line); pending.get(message.id)?.(message.result); pending.delete(message.id);
});
function request(payload) {
  const id=++sequence;
  return new Promise(resolve => { pending.set(id,resolve); process.stdout.write(JSON.stringify({id,...payload})+'\n'); });
}
async function consoleCommand(command) { await request({console:command}); await sleep(350); }
async function sql(query,params=[]) { return request({sql:query,params}); }
function pass(name) { process.stdout.write(JSON.stringify({status:'pass',test:name})+'\n'); }
async function until(check, message, timeout=8000) {
  const deadline=Date.now()+timeout;
  while (Date.now()<deadline) { if (await check()) return; await sleep(80); }
  throw new Error('Timed out: '+message);
}
const bots=[];
async function connect(username) {
  // minecraft-data's 1.21.1 recipe decoder currently fails on Paper's trim recipes.
  // Recipe synchronization is outside these tests; retain its opaque bytes rather than parse it.
  // Inventory contents, item components, clicks and block packets use the unmodified protocol.
  const customPackets={'1.21':{play:{toClient:{types:{packet_declare_recipes:'restBuffer'}}}}};
  const bot=mineflayer.createBot({host:'127.0.0.1',port:Number(process.env.CLOUD_TEST_PORT),username,version:'1.21.1',auth:'offline',checkTimeoutInterval:30000,customPackets});
  bots.push(bot); bot.messages=[];
  for (const event of ['window_items','set_slot']) bot._client.on(event,p=>{ if (p.stateId!==undefined) bot.lastWindowStateId=p.stateId; });
  bot.on('messagestr',text=>bot.messages.push(text));
  bot.on('error',error=>process.stderr.write(username+': '+error.message+'\n'));
  bot.on('kicked',reason=>process.stderr.write(username+' kicked: '+JSON.stringify(reason)+'\n'));
  await Promise.race([once(bot,'spawn'),sleep(30000).then(()=>{throw new Error('spawn timeout '+username);})]);
  await sleep(500); return bot;
}
async function command(bot,text,expected) {
  // Paper charges commands to the chat spam counter even while the player is temporarily OP.
  // Realistic pacing keeps later permission-revocation assertions independent of that counter.
  if (bot.lastCommand) await sleep(Math.max(0,1100-(Date.now()-bot.lastCommand)));
  bot.lastCommand=Date.now();
  const start=bot.messages.length;
  bot.chat(text);
  if (expected) await until(()=>bot.messages.slice(start).some(m=>m.includes(expected)),text+' -> '+expected+'; '+bot.messages.slice(start).join(' | '));
  await sleep(120);
  return bot.messages.slice(start);
}
function count(bot,name) { return bot.inventory.items().filter(i=>i.name===name).reduce((s,i)=>s+i.count,0); }
async function total(material,vault) {
  const params=[material]; if (vault) params.push(vault);
  const rows=await sql('SELECT COALESCE(SUM(s.amount),0) AS n FROM stock s JOIN items i USING(key) WHERE i.material=?'+(vault?' AND s.vault=?':''),params);
  return rows[0].n;
}
async function clear(bot) { await consoleCommand('clear '+bot.username); await until(()=>bot.inventory.items().length===0,'inventory clear'); }
async function open(bot) {
  const opened=once(bot,'windowOpen'); bot.chat('/cs open'); const [window]=await opened; await sleep(200); return window;
}
async function close(bot) { if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await sleep(200); } }
async function click(bot,slot,button=0,mode=0) { await bot.clickWindow(slot,button,mode); await sleep(250); }
async function equip(bot,name) { const item=bot.inventory.items().find(i=>i.name===name); assert.ok(item,'missing '+name); await bot.equip(item,'hand'); await sleep(200); }

async function coexist(alice,restart) {
  await consoleCommand('deop CloudAlice');
  await command(alice,'/cs status','shulker-isolation=ready');
  const catalog=()=>alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const openItem=async(name)=>{
    const item=alice.inventory.items().find(i=>i.name===name); assert.ok(item);
    const opened=once(alice,'windowOpen'); await click(alice,item.slot,1,0);
    const [window]=await opened; await sleep(200); return window;
  };
  if (!restart) {
    await clear(alice);
    await consoleCommand('give CloudAlice minecraft:stone 128');
    await command(alice,'/cs deposit all','deposit=128');
    await command(alice,'/cs shulker stone','云潜影盒已绑定');
    await consoleCommand('item replace entity CloudAlice inventory.1 with minecraft:blue_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:stone",count:32}}]]');
  }
  assert.equal(await total('STONE'),128);
  const original=count(alice,'stone');
  for (let cycle=0;cycle<3;cycle++) {
    const window=await openItem('purple_shulker_box');
    assert.equal(window.slots[0]?.name,'stone');
    await click(alice,0,0,0);
    assert.equal(alice.currentWindow.selectedItem?.count,64);
    assert.equal(await total('STONE'),64);
    const empty=window.slots.findIndex((i,index)=>index>=27 && i===null);
    await click(alice,empty,0,0);
    const source=window.slots.findIndex((i,index)=>index>=27 && i?.name==='stone' && i.count===64);
    await click(alice,source,0,0); await click(alice,26,0,0);
    assert.equal(await total('STONE'),128);
    await close(alice);
    assert.equal(count(alice,'stone'),original);
    assert.ok(!JSON.stringify(catalog()).includes('AxShulkers-UUID'),'cloud shell must not acquire Ax identity');
  }
  pass('AxShulkers installed: repeated cloud PICKUP/return conserves ledger and never registers the catalogue as a physical shulker');
  let window=await openItem('blue_shulker_box');
  assert.equal(window.slots[0]?.name,'stone');
  assert.equal(window.slots[0]?.count,32);
  await click(alice,0,0,1); await close(alice);
  assert.equal(count(alice,'stone'),original+32);
  assert.equal(await total('STONE'),128);
  window=await openItem('blue_shulker_box');
  const source=window.slots.findIndex((i,index)=>index>=27 && i?.name==='stone' && i.count===32);
  await click(alice,source,0,1); await close(alice);
  assert.equal(count(alice,'stone'),original);
  pass('non-OP ordinary shulker opens through AxShulkers and takes/returns physical stock without touching cloud ledger');
  await equip(alice,'purple_shulker_box');
  const tokenCount=count(alice,'purple_shulker_box');
  alice._client.write('block_dig',{status:4,location:{x:0,y:0,z:0},face:0,sequence:0});
  await sleep(300); assert.equal(count(alice,'purple_shulker_box'),tokenCount);
  const opened=once(alice,'windowOpen'); alice.activateItem(); await opened; await sleep(250);
  assert.equal(alice.currentWindow.slots[0]?.name,'stone');
  await command(alice,'/cs shulker off','移除=1');
  assert.equal(catalog(),undefined);
  assert.equal(await total('STONE'),128);
  await command(alice,'/cs shulker stone','云潜影盒已绑定');
  const bob=await connect('CloudBob');
  const token=catalog();
  const tokenSource=token.slot>=36?'hotbar.'+(token.slot-36):'inventory.'+(token.slot-9);
  await consoleCommand('item replace entity CloudBob inventory.0 from entity CloudAlice '+tokenSource);
  await click(bob,9,1,0); assert.equal(bob.currentWindow,null);
  await consoleCommand('clear CloudBob minecraft:purple_shulker_box');
  const pending=await sql("SELECT count(*) n FROM journal WHERE state='PENDING'"); assert.equal(pending[0].n,0);
  pass('hand opening and cloud-off command bypass Ax command restriction; foreign UUID remains blocked; no pending transfers');
}

async function coexistBlocked(alice) {
  await consoleCommand('deop CloudAlice');
  await command(alice,'/cs status','shulker-isolation=blocked');
  await command(alice,'/cs shulker','隔离未就绪');
  const token=alice.inventory.items().find(i=>i.name==='purple_shulker_box'); assert.ok(token);
  await sleep(1200); await click(alice,token.slot,1,0); assert.equal(alice.currentWindow,null);
  assert.equal(await total('STONE'),128);
  const ordinary=alice.inventory.items().find(i=>i.name==='blue_shulker_box'); assert.ok(ordinary);
  const opened=once(alice,'windowOpen'); await click(alice,ordinary.slot,1,0); await opened;
  assert.equal(alice.currentWindow.slots[0]?.count,32); await close(alice);
  pass('unsupported Ax version blocks cloud issuance/opening but preserves ordinary shulker and balances');
}

async function portQuotas(alice) {
  await consoleCommand('deop CloudAlice');
  await command(alice,'/cs shulker off','云潜影盒已关闭');
  await clear(alice);
  await command(alice,'/cs mode shared','mode=shared');
  await consoleCommand('tp CloudAlice 0.5 -60 4.5');
  await consoleCommand('give CloudAlice minecraft:barrel 12');
  await equip(alice,'barrel');
  const Vec3=require('vec3').Vec3;
  for (const z of [1,2]) for (let x=-2;x<=3;x++) {
    await consoleCommand(`setblock ${x} -60 ${z} minecraft:air`);
    await consoleCommand(`setblock ${x} -61 ${z} minecraft:stone`);
    const floor=alice.blockAt(new Vec3(x,-61,z));
    await alice.placeBlock(floor,new Vec3(0,1,0)); await sleep(150);
    assert.equal(alice.blockAt(new Vec3(x,-60,z)).name,'barrel');
  }
  await consoleCommand('give CloudAlice minecraft:brick 1'); await equip(alice,'brick');
  const aim=async(bot,x,z)=>{ await bot.lookAt(new Vec3(x+0.5,-59.05,z+0.5)); await sleep(100); };
  for (let x=-2;x<=2;x++) { await aim(alice,x,1); await command(alice,'/cs port input','port=input bound'); }
  await aim(alice,3,1); await command(alice,'/cs port input','输入接口上限（5 个）');
  // Changing selected vault cannot evade a UUID's quota; output has its independent allowance.
  await command(alice,'/cs mode personal','mode=personal');
  await command(alice,'/cs port input','输入接口上限（5 个）');
  for (let x=-2;x<=2;x++) { await aim(alice,x,2); await command(alice,'/cs port output','port=output bound'); }
  await aim(alice,3,2); await command(alice,'/cs port output','输出接口上限（5 个）');
  await command(alice,'/cs port list','输入=5/5，输出=5/5');
  let ports=await sql('SELECT * FROM ports'); assert.equal(ports.length,10);
  assert.equal(ports.filter(p=>p.mode==='input').length,5); assert.equal(ports.filter(p=>p.mode==='output').length,5);
  await aim(alice,-2,1); await command(alice,'/cs port unbind','port=unbound');
  await aim(alice,3,1); await command(alice,'/cs port input','port=input bound');
  await aim(alice,-2,1); await command(alice,'/cs port input','输入接口上限（5 个）');
  const peer=await connect('CloudPeer');
  await command(peer,'/cs mode','mode=shared');
  await consoleCommand('tp CloudPeer 4.5 -60 4.5');
  await consoleCommand('setblock 4 -60 2 minecraft:air');
  await consoleCommand('setblock 4 -61 2 minecraft:stone');
  await consoleCommand('give CloudPeer minecraft:barrel 1'); await equip(peer,'barrel');
  await peer.placeBlock(peer.blockAt(new Vec3(4,-61,2)),new Vec3(0,1,0)); await sleep(200);
  await aim(peer,4,2); await command(peer,'/cs port input','port=input bound');
  await command(peer,'/cs port list','输入=1/5，输出=0/5');
  await consoleCommand('tp CloudPeer 0.5 -60 4.5');
  await aim(peer,-1,1); await command(peer,'/cs port unbind','error=接口不属于此 UUID');
  assert.equal((await sql('SELECT * FROM ports')).length,11);
  await command(alice,'/cs shulker auto','云潜影盒已绑定');
  assert.equal(await total('STONE'),192);
  pass('non-OP players bind 5 inputs plus 5 outputs; sixth of each rejected across vaults; unbind frees capacity; UUID quotas/ownership isolated');
}

async function main() {
  const alice=await connect('CloudAlice');
  await consoleCommand('op CloudAlice');
  const phase=process.env.CLOUD_TEST_PHASE;
  if (phase==='coexist' || phase==='coexist-restart') { await coexist(alice,phase==='coexist-restart'); return; }
  if (phase==='coexist-blocked') { await coexistBlocked(alice); return; }
  if (phase==='guard') {
    await command(alice,'/cs status','error=云仓初始化失败');
    const token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
    assert.ok(token,'existing cloud shell survives a schema initialization failure');
    await click(alice,token.slot,1,0);
    assert.equal(alice.currentWindow,null);
    pass('database initialization failure keeps cloud token guards active'); return;
  }
  if (phase==='restart') {
    await command(alice,'/cs status','frozen=false');
    assert.equal(await total('STONE'),192);
    await command(alice,'/cs mode','mode=personal');
    await consoleCommand('deop CloudAlice');
    await command(alice,'/cs port list','输入=5/5，输出=5/5');
    assert.equal((await sql('SELECT * FROM ports')).length,11);
    await command(alice,'/cs withdraw stone 1','withdraw=1');
    await equip(alice,'stone'); // Token migration can change which hotbar slot is selected across login.
    await command(alice,'/cs deposit 1','deposit=1');
    assert.equal(await total('STONE'),192);
    pass('clean restart preserves stock, UUID mode and usable ledger'); return;
  }
  if (phase==='crash') {
    await command(alice,'/cs status','frozen=true');
    const before=count(alice,'stone');
    await command(alice,'/cs withdraw stone 1','error=');
    assert.equal(count(alice,'stone'),before); assert.equal(await total('STONE'),192);
    const unresolved=await sql("SELECT count(*) n FROM journal WHERE state='PENDING'"); assert.equal(unresolved[0].n,0);
    await consoleCommand('cs recover unlock');
    await command(alice,'/cs status','frozen=false');
    pass('forced stop freezes transfers until operator audit; no item replay'); return;
  }
  if (phase==='ports') { await portQuotas(alice); return; }

  const bob=await connect('CloudBob');
  await command(bob,'/cs mode','mode=shared');
  await command(bob,'/cs mode personal','mode=personal');
  await command(alice,'/cs mode personal','mode=personal');
  await consoleCommand('tp CloudAlice 0 -60 0');
  await consoleCommand('tp CloudBob 4 -60 0');
  await clear(alice); await clear(bob);
  await command(alice,'/cs status','frozen=false');
  await consoleCommand('give CloudAlice minecraft:stone 128');
  await until(()=>count(alice,'stone')===128,'128 stone received');
  await command(alice,'/cs deposit all','deposit=128');
  assert.equal(count(alice,'stone'),0); assert.equal(await total('STONE'),128);
  await command(alice,'/cs withdraw stone 64','withdraw=64');
  assert.equal(count(alice,'stone'),64); assert.equal(await total('STONE'),64);
  pass('command deposit/withdraw conserves items');

  await command(bob,'/cs withdraw stone 1','error=');
  assert.equal(count(bob,'stone'),0);
  await command(bob,'/cs mode shared','mode=shared');
  await command(bob,'/cs mode personal','mode=personal');
  pass('UUID personal isolation and non-OP default shared access/switching');

  let window=await open(alice);
  let slot=window.slots.findIndex((i,index)=>index<45 && i?.name==='stone');
  assert.ok(slot>=0); await click(alice,slot,0,1);
  await until(()=>count(alice,'stone')===128,'GUI withdraw');
  assert.equal(await total('STONE'),0);
  slot=alice.currentWindow.slots.findIndex((i,index)=>index>=window.inventoryStart && i?.name==='stone');
  await click(alice,slot,0,1); await until(async()=>await total('STONE')===64,'GUI deposit');
  assert.equal(count(alice,'stone'),64);
  // Footer buttons and unsupported hotbar/drop clicks cannot become real items.
  await click(alice,49,0,0);
  await click(alice,0,0,2); // number-key swap
  await click(alice,0,0,4); // drop
  assert.equal(await total('STONE'),64); assert.equal(count(alice,'stone'),64);
  assert.equal(count(alice,'clock'),0); assert.equal(count(alice,'arrow'),0);
  await close(alice);
  pass('54-slot GUI transfer and rejected hotbar/drop/button theft');

  await consoleCommand('give CloudAlice minecraft:cobblestone 2240');
  await until(()=>alice.inventory.items().length===36,'full inventory');
  await command(alice,'/cs withdraw stone 1','withdraw=0');
  assert.equal(await total('STONE'),64); assert.equal(count(alice,'stone'),64);
  await consoleCommand('clear CloudAlice minecraft:cobblestone');
  pass('full inventory withdrawal delivers nothing and preserves cloud balance');

  await command(alice,'/cs withdraw stone -1','error=');
  await command(alice,'/cs withdraw stone 2147483647','error=');
  assert.equal(await total('STONE'),64);
  pass('negative/oversized quantities leave ledger unchanged');

  await command(alice,'/cs deposit all','deposit=64');
  await command(alice,'/cs mode shared','mode=shared');
  await consoleCommand('give CloudAlice minecraft:dirt 64');
  await command(alice,'/cs deposit all','deposit=64');
  await command(bob,'/cs mode shared','mode=shared');
  // Two clients race against one 64-item balance. Total delivery remains exactly 64.
  alice.chat('/cs withdraw dirt 64'); bob.chat('/cs withdraw dirt 64');
  await until(()=>count(alice,'dirt')+count(bob,'dirt')===64,'shared race');
  assert.equal(await total('DIRT'),0);
  pass('shared multi-client withdrawal cannot overdraw');
  await command(bob,'/cs mode personal','mode=personal');
  pass('ordinary player can return from shared to own UUID vault');
  await command(alice,'/cs mode personal','mode=personal');

  await command(alice,'/cs shulker','云潜影盒已绑定');
  await command(alice,'/cs shulker demand minecraft:stone');
  let token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  assert.ok(token);
  assert.ok(token.slot>=9 && token.slot<36,'new token must be discoverable without manually moving it out of the hotbar');
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const catalog=token.components.find(c=>c.type==='container');
  assert.ok(catalog,'printer discovery requires a real client-visible container component');
  assert.ok(JSON.stringify(catalog).includes('"itemCount":64'),'catalog contains printable stack counts');
  const opened=once(alice,'windowOpen');
  await click(alice,token.slot,1,0); // Same PICKUP right-click path as the reference printer.
  [window]=await opened; await sleep(200);
  assert.equal(window.inventoryStart,27);
  slot=window.slots.findIndex((i,index)=>index<27 && i?.name==='stone'); assert.ok(slot>=0);
  const stockBefore=await total('STONE'); const playerBefore=count(alice,'stone');
  const shownBefore=window.slots[slot].count;
  await click(alice,slot,0,1); // QUICK_MOVE without manipulating directory items.
  await until(()=>count(alice,'stone')===playerBefore+shownBefore,'printer quick move');
  assert.equal(await total('STONE'),stockBefore-shownBefore);
  assert.equal(alice.currentWindow.slots[slot]?.count??0,0);
  let returnedSlot=alice.currentWindow.slots.findIndex((i,index)=>index>=27 && i?.name==='stone');
  await click(alice,returnedSlot,0,1);
  await until(()=>count(alice,'stone')===playerBefore,'printer return');
  assert.equal(await total('STONE'),stockBefore);
  assert.equal(alice.currentWindow.slots[slot]?.count??0,shownBefore);
  await close(alice);
  await command(alice,'/cs deposit all','deposit=');
  assert.equal(await total('STONE'),128);
  pass('printer right-click + QUICK_MOVE take/return uses DB balance and count acknowledgements');

  // The user's actual 1.3-beta.4-local printer uses PICKUP -> empty player slot, NOT QUICK_MOVE.
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const legacyOpen=once(alice,'windowOpen');
  await click(alice,token.slot,1,0); [window]=await legacyOpen; await sleep(150);
  slot=window.slots.findIndex((i,index)=>index<27 && i?.name==='stone'); assert.ok(slot>=0);
  const legacyBefore=await total('STONE');
  await click(alice,slot,0,0);
  assert.equal(alice.currentWindow.selectedItem?.name,'stone');
  assert.equal(alice.currentWindow.selectedItem?.count,64);
  assert.equal(await total('STONE'),legacyBefore-64);
  const destination=alice.currentWindow.slots.findIndex((i,index)=>index>=27 && i===null);
  assert.ok(destination>=27);
  await click(alice,destination,0,0);
  assert.equal(alice.currentWindow.selectedItem,null);
  await until(()=>count(alice,'stone')===64,'legacy cursor delivery');
  // Return via the exact inverse two-PICKUP sequence into the reserved empty shulker slot.
  const source=alice.currentWindow.slots.findIndex((i,index)=>index>=27 && i?.name==='stone');
  await click(alice,source,0,0);
  const empty=alice.currentWindow.slots.findIndex((i,index)=>index<27 && i===null);
  await click(alice,empty,0,0);
  assert.equal(alice.currentWindow.selectedItem,null);
  assert.equal(count(alice,'stone'),0);
  assert.equal(await total('STONE'),legacyBefore);
  await close(alice);
  pass('installed legacy printer PICKUP cursor -> inventory -> cursor -> cloud conserves items');

  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const burstOpen=once(alice,'windowOpen'); await click(alice,token.slot,1,0); [window]=await burstOpen;
  slot=window.slots.findIndex((i,index)=>index<27 && i?.name==='stone');
  const burstDestination=window.slots.findIndex((i,index)=>index>=27 && i===null);
  const Item=require('prismarine-item')(alice.registry);
  const stateId=alice.lastWindowStateId;
  alice._client.write('window_click',{windowId:window.id,stateId,slot,mouseButton:0,mode:0,changedSlots:[],cursorItem:Item.toNotch(window.slots[slot])});
  alice._client.write('window_click',{windowId:window.id,stateId,slot:burstDestination,mouseButton:0,mode:0,changedSlots:[],cursorItem:Item.toNotch(null)});
  alice.closeWindow(window); // The installed printer immediately closes, before receiving either reply.
  await alice._syncWindow(alice.inventory); // Read the actual result; raw writes deliberately had no local prediction.
  await until(()=>count(alice,'stone')===64,'two legacy PICKUP packets sent without waiting for acknowledgement');
  assert.equal(await total('STONE'),legacyBefore-64);
  await close(alice);
  await command(alice,'/cs deposit all','deposit=64');
  assert.equal(await total('STONE'),legacyBefore);
  pass('legacy back-to-back predicted clicks settle correctly even with the same state id');

  await consoleCommand('give CloudAlice minecraft:smooth_stone 64');
  await consoleCommand('give CloudAlice minecraft:smooth_stone_slab 64');
  await command(alice,'/cs deposit all','deposit=128');
  await command(alice,'/cs shulker smooth_stone smooth_stone_slab','目录=SMOOTH_STONE,SMOOTH_STONE_SLAB');
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const filteredOpen=once(alice,'windowOpen'); await click(alice,token.slot,1,0); [window]=await filteredOpen;
  const filtered=window.slots.slice(0,27).filter(Boolean).map(i=>i.name).sort();
  assert.deepEqual(filtered,['smooth_stone','smooth_stone_slab']);
  assert.equal(window.slots[26],null);
  await close(alice);
  await command(alice,'/cs shulker auto','目录=auto');
  await command(alice,'/cs shulker demand minecraft:smooth_stone_slab');
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const demandOpen=once(alice,'windowOpen'); await click(alice,token.slot,1,0); [window]=await demandOpen;
  assert.deepEqual(window.slots.slice(0,27).filter(Boolean).map(i=>i.name),['smooth_stone_slab']);
  assert.equal(window.slots.slice(0,27).filter(i=>i===null).length,26);
  await close(alice);
  pass('on-demand catalogue exposes exactly the requested block and 26 return slots');

  window=await open(alice);
  assert.equal(window.slots[48]?.name,'compass');
  const bobMessages=bob.messages.length;
  await click(alice,48);
  await until(()=>!alice.currentWindow,'search prompt closes menu');
  const searchOpened=once(alice,'windowOpen'); alice.chat('平滑石'); [window]=await searchOpened;
  assert.deepEqual(window.slots.slice(0,45).filter(Boolean).map(i=>i.name).sort(),['smooth_stone','smooth_stone_slab']);
  assert.ok(!bob.messages.slice(bobMessages).some(m=>m.includes('平滑石')),'search text must stay private');
  await click(alice,48,1);
  await until(()=>alice.currentWindow?.slots.slice(0,45).some(i=>i?.name==='stone'),'right click clears search');
  await close(alice);
  const chineseOpened=once(alice,'windowOpen'); alice.chat('/cs search 平滑石台阶'); [window]=await chineseOpened;
  assert.deepEqual(window.slots.slice(0,45).filter(Boolean).map(i=>i.name),['smooth_stone_slab']);
  await close(alice);
  await command(alice,'/cs withdraw 平滑石台阶 1','withdraw=1');
  await command(alice,'/cs deposit all','deposit=1');
  pass('search button private chat, Chinese partial name, right-click clear and Chinese withdraw work');
  pass('smooth stone/slab material filter bypasses alphabetic first-page restriction and reserves return space');

  // Reissuing upgrades a token left in the hotbar, without a timer moving deliberate hand actions.
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  await alice.moveSlotItem(token.slot,44);
  await command(alice,'/cs shulker auto','云潜影盒已绑定');
  await until(()=>alice.inventory.items().some(i=>i.name==='purple_shulker_box' && i.slot>=9 && i.slot<36),'hotbar token migration');
  pass('updating a hotbar cloud token migrates it into the slots scanned by the printer');
  await equip(alice,'purple_shulker_box');
  const tokenCount=count(alice,'purple_shulker_box');
  alice._client.write('block_dig',{status:4,location:{x:0,y:0,z:0},face:0,sequence:0});
  await sleep(500);
  assert.equal(count(alice,'purple_shulker_box'),tokenCount);
  const vector=require('vec3').Vec3;
  const floor=alice.blockAt(new vector(2,-61,0));
  await alice._genericPlace(floor,new vector(0,1,0),{swingArm:'right'});
  await sleep(500);
  assert.notEqual(alice.blockAt(new vector(2,-60,0)).name,'purple_shulker_box');
  await close(alice);
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const tokenSource=token.slot>=36?'hotbar.'+(token.slot-36):'inventory.'+(token.slot-9);
  await consoleCommand('item replace entity CloudBob inventory.0 from entity CloudAlice '+tokenSource);
  assert.equal(count(bob,'purple_shulker_box'),1);
  await click(bob,9,1,0); assert.equal(bob.currentWindow,null);
  assert.equal(await total('STONE'),128);
  await consoleCommand('clear CloudBob minecraft:purple_shulker_box');
  pass('cloud token cannot drop, place or be opened by a different UUID');

  // Off frees the shell slot while a real, debited cursor stack is returned by Paper.
  await command(alice,'/cs shulker auto','云潜影盒已绑定');
  await command(alice,'/cs shulker demand minecraft:stone');
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  const offOpen=once(alice,'windowOpen'); await click(alice,token.slot,1,0); [window]=await offOpen;
  slot=window.slots.findIndex((i,index)=>index<27 && i?.name==='stone');
  const beforeOff=await total('STONE');
  await click(alice,slot,0,0);
  assert.equal(alice.currentWindow.selectedItem?.count,64);
  await command(alice,'/cs shulker off','云潜影盒已关闭，移除=1');
  await until(()=>!alice.currentWindow && count(alice,'stone')===64,'off returns real cursor material');
  assert.equal(count(alice,'purple_shulker_box'),0);
  assert.equal(await total('STONE'),beforeOff-64);
  await command(alice,'/cs deposit all','deposit=64');
  assert.equal(await total('STONE'),beforeOff);
  await command(alice,'/cs shulker off','移除=0');
  await command(alice,'/cs shulker','云潜影盒已绑定');
  token=alice.inventory.items().find(i=>i.name==='purple_shulker_box');
  await click(alice,token.slot,0,0); // Hold the discovery shell on the inventory cursor.
  assert.equal(alice.inventory.selectedItem?.name,'purple_shulker_box');
  await command(alice,'/cs shulker off','移除=1');
  await alice._syncWindow(alice.inventory);
  assert.equal(alice.inventory.selectedItem,null);
  assert.equal(count(alice,'purple_shulker_box'),0);
  assert.equal(await total('STONE'),beforeOff);
  // A regular non-OP player may discard a shell bound to the shared vault.
  await command(alice,'/cs mode shared','mode=shared');
  await command(alice,'/cs shulker','云潜影盒已绑定');
  await consoleCommand('deop CloudAlice');
  await command(alice,'/cs shulker off','移除=1');
  assert.equal(count(alice,'purple_shulker_box'),0);
  await consoleCommand('op CloudAlice');
  await command(alice,'/cs mode personal','mode=personal');
  await command(alice,'/cs shulker','云潜影盒已绑定');
  pass('shulker off removes shells/cursor without minting catalog stock, preserves withdrawn cursor and permits re-enable');

  // Special items never enter any new deposit path; plain resources remain accepted.
  await consoleCommand('give CloudAlice minecraft:stone[minecraft:custom_name=\'{"text":"Named Stone"}\'] 8');
  await consoleCommand('give CloudAlice minecraft:stone[minecraft:custom_data={test_uuid:"special"}] 4');
  await consoleCommand('give CloudAlice minecraft:diamond_sword 1');
  await consoleCommand('give CloudAlice minecraft:shulker_box 1');
  await consoleCommand('give CloudAlice minecraft:player_head 1');
  await command(alice,'/cs deposit all','deposit=0');
  assert.equal(count(alice,'stone'),12);
  assert.equal(count(alice,'diamond_sword'),1);
  assert.equal(count(alice,'shulker_box'),1);
  assert.equal(count(alice,'player_head'),1);
  const variants=await sql("SELECT i.key,s.amount FROM stock s JOIN items i USING(key) WHERE i.material='STONE'");
  assert.equal(variants.length,1);
  await equip(alice,'diamond_sword');
  await command(alice,'/cs deposit','error=');
  window=await open(alice);
  slot=window.slots.findIndex((i,index)=>index>=54 && i?.name==='diamond_sword');
  await click(alice,slot,0,1);
  assert.equal(count(alice,'diamond_sword'),1);
  await close(alice);
  await consoleCommand('clear CloudAlice minecraft:stone'); // Remove test-only named stones; ordinary cloud balance unchanged.
  for (const special of ['diamond_sword','shulker_box','player_head']) await consoleCommand('clear CloudAlice minecraft:'+special);
  assert.equal(await total('STONE'),128);
  pass('named/NBT resources, equipment, shulkers and player heads reject command and GUI deposits without loss');

  // Place and bind genuine world interfaces through normal client block placement.
  await consoleCommand('deop CloudAlice');
  await consoleCommand('setblock 2 -61 0 minecraft:stone');
  await consoleCommand('setblock 2 -60 0 minecraft:air');
  await consoleCommand('give CloudAlice minecraft:barrel 1');
  await equip(alice,'barrel');
  const ground=alice.blockAt(new (require('vec3').Vec3)(2,-61,0)); assert.ok(ground);
  await alice.lookAt(ground.position.offset(0.5,1,0.5));
  await alice.placeBlock(ground,new (require('vec3').Vec3)(0,1,0));
  await sleep(500);
  const barrel=alice.blockAt(ground.position.offset(0,1,0)); assert.equal(barrel.name,'barrel');
  await alice.lookAt(barrel.position.offset(0.5,0.5,0.5));
  await command(alice,'/cs port input','port=input bound');
  await consoleCommand('item replace block 2 -60 0 container.0 with minecraft:stone 64');
  await until(async()=>await total('STONE')===192,'world input',12000);
  await consoleCommand('item replace block 2 -60 0 container.1 with minecraft:diamond_sword');
  await sleep(1200);
  const specialStock=await total('DIAMOND_SWORD'); assert.equal(specialStock,0);
  const guardedBarrel=await alice.openContainer(alice.blockAt(new vector(2,-60,0)));
  assert.ok(guardedBarrel.containerItems().some(i=>i.name==='diamond_sword'));
  guardedBarrel.close(); await sleep(150);
  await consoleCommand('item replace block 2 -60 0 container.1 with minecraft:air');
  const p=await sql('SELECT * FROM ports'); assert.equal(p.length,1); assert.equal(p[0].mode,'input');
  await alice.lookAt(barrel.position.offset(0.5,0.5,0.5));
  await command(alice,'/cs port unbind','port=unbound');
  await command(alice,'/cs withdraw stone 1','withdraw=1');
  await equip(alice,'stone');
  await consoleCommand('setblock 2 -61 0 minecraft:hopper[facing=east]');
  await alice.lookAt(barrel.position.offset(0.5,0.5,0.5));
  await command(alice,'/cs port output','port=output bound');
  await until(async()=>await total('STONE')<191,'world output',12000);
  await sleep(1500);
  const hopper=await alice.openContainer(alice.blockAt(new vector(2,-61,0)));
  assert.equal(hopper.containerItems().length,0,'unowned hopper cannot drain a private port');
  hopper.close(); await sleep(150);
  await consoleCommand('setblock 2 -61 0 minecraft:stone');
  await alice.lookAt(barrel.position.offset(0.5,0.5,0.5));
  await command(alice,'/cs port unbind','port=unbound');
  // Return all physical stones from the output barrel so total conservation is deterministic.
  const chest=await alice.openContainer(barrel);
  for (const item of chest.containerItems()) if (item.name==='stone') await chest.withdraw(item.type,null,item.count);
  chest.close(); await sleep(250);
  await command(alice,'/cs deposit all','deposit=');
  assert.equal(await total('STONE'),192);
  pass('owned barrel input/output conserves inventory; unowned hopper extraction is blocked');

  if (process.env.CLOUD_TEST_PLACEMENT==='1') {
    await consoleCommand('give CloudAlice minecraft:hopper 2');
    await consoleCommand('give CloudAlice minecraft:oak_sign 2');
    await command(alice,'/cs deposit all','deposit=4');
    await command(alice,'/cs withdraw hopper 2','withdraw=2');
    await equip(alice,'hopper');
    await consoleCommand('setblock 2 -60 0 minecraft:air');
    // Carpet V2 encodes EAST=5 in cursor X: 2 + 2*5. Plain Paper rejects this packet.
    const encodedPlace=(location,direction,cursorX,seq)=>alice._client.write('block_place',{
      hand:0,location,direction,cursorX,cursorY:1,cursorZ:0.5,insideBlock:false,sequence:seq
    });
    encodedPlace({x:2,y:-61,z:0},1,12,500);
    await until(()=>alice.blockAt(new vector(2,-60,0))?.name==='hopper','V2 hopper placement');
    assert.equal(alice.blockAt(new vector(2,-60,0)).getProperties().facing,'east');
    await until(()=>count(alice,'hopper')===1,'exactly one hopper consumed in survival');
    assert.equal(await total('HOPPER'),0);
    await command(alice,'/cs deposit all','deposit=1');
    assert.equal(await total('HOPPER'),1);
    await command(alice,'/cs withdraw oak_sign 2','withdraw=2');
    await equip(alice,'oak_sign');
    for(let i=0;i<5;i++) { encodedPlace({x:2,y:-58,z:0},1,2,501+i); await sleep(150); }
    await alice._syncWindow(alice.inventory);
    assert.equal(count(alice,'oak_sign'),2,'unsupported placements must not consume signs');
    assert.equal(alice.blockAt(new vector(2,-57,0)).name,'air');
    assert.equal(await total('OAK_SIGN'),0);
    await command(alice,'/cs deposit all','deposit=2');
    assert.equal(await total('OAK_SIGN'),2);
    // Normal supported sign placement is still accepted and charged exactly once.
    await command(alice,'/cs withdraw oak_sign 1','withdraw=1');
    await equip(alice,'oak_sign');
    await consoleCommand('setblock 1 -61 1 minecraft:stone');
    encodedPlace({x:1,y:-61,z:1},1,2,506);
    await until(()=>alice.blockAt(new vector(1,-60,1))?.name==='oak_sign','V2 supported sign placement');
    await until(()=>count(alice,'oak_sign')===0,'one supported sign consumed');
    assert.equal(await total('OAK_SIGN'),1);
    pass('V2 encoded hopper facing accepted; failed sign placements conserve stock; supported sign consumes exactly one');
  }

  const unresolved=await sql("SELECT count(*) n FROM journal WHERE state='PENDING'");
  assert.equal(unresolved[0].n,0);
  await command(alice,'/cs mode','mode=personal');
  pass('all completed transfers settled with no pending journal');
}

const watchdog=setTimeout(()=>{process.stderr.write('Integration watchdog timeout\n');process.exit(2);},240000);
main().then(async()=>{
  for (const bot of bots) bot.quit(); await sleep(500); clearTimeout(watchdog); process.exit(0);
}).catch(async error=>{
  process.stdout.write(JSON.stringify({status:'fail',error:error.stack})+'\n');
  for (const bot of bots) bot.quit(); await sleep(300); process.exit(1);
});
