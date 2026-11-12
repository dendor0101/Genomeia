// node lwjgl3/src/verification/mcp/smoke.mjs <desktop.jar> [GoMenu|GoGenomeEditor|GoSimulation]
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, copyFile, writeFile, readFile, readdir } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { resolve, join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';

const dir = await mkdtemp(join(tmpdir(), 'genomeia-mcp-'));
const jar = join(dir, 'Genomeia.test'); // .map association is only registered for .jar releases.
await copyFile(resolve(process.argv[2]), jar);
await mkdir(join(dir, 'home'));
const route = process.argv[3] || 'GoMenu';
const jvm = ['-Duser.home=' + join(dir, 'home'), '--enable-native-access=ALL-UNNAMED'];
// Use an existing, fast-growing library genome instead of relying on the first bundled name.
if (route === 'GoSimulation') {
    await mkdir(join(dir,'genomes'));
    await copyFile(new URL('../../../../assets/genomes/Hydrofin.genome', import.meta.url),join(dir,'genomes','A.genome'));
}
const game = spawn('java', [...jvm, '-Dgenomeia.start=' + route, ...(route === 'GoSimulation' ? ['-Dgenomeia.clickAt=20:410,390;55:420,370'] : []), '-jar', jar, '--enable-mcp'],
    { cwd: dir, stdio: ['ignore', 'pipe', 'pipe'] });
const log = createWriteStream(join(dir, 'game.log'));
game.stdout.pipe(log, { end: false });
game.stderr.pipe(log, { end: false });
const server = spawn('java', [...jvm, '-jar', jar, '--mcp'], { cwd: dir, stdio: ['pipe', 'pipe', 'inherit'] });
const messages = createInterface({ input: server.stdout })[Symbol.asyncIterator]();
let seq = 0;
async function rpc(method, params) {
    const id = ++seq;
    server.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    const response = JSON.parse((await messages.next()).value);
    assert.equal(response.id, id);
    assert.equal(response.error, undefined, JSON.stringify(response.error));
    return response.result;
}
const raw = (name, args = {}) => rpc('tools/call', { name, arguments: args });
async function tool(name, args) {
    const r = await raw(name, args);
    assert.ok(!r.isError, JSON.stringify(r.content));
    return r.structuredContent ?? r.content;
}
const timeout = setTimeout(() => { server.kill(); game.kill(); }, 120000);
try {
    const init = await rpc('initialize', { protocolVersion:'2025-11-25', capabilities:route==='GoSimulation'?{elicitation:{form:{}}}:{}, clientInfo:{name:'smoke',version:'1'} });
    assert.equal(init.protocolVersion, '2025-11-25');
    server.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n');
    assert.ok((await rpc('tools/list', {})).tools.length >= 9);
    let status;
    for (let i = 0; i < 60; i++) {
        const r = await raw('get_status');
        if (!r.isError) { status = r.structuredContent; break; }
        assert.equal(game.exitCode, null, 'Game failed. Inspect ' + dir);
        await delay(500);
    }
    assert.ok(status, 'Game did not connect: ' + dir);
    if (route === 'GoMenu') {
        const catalog = await tool('get_catalog');
        const lib = await tool('list_genomes');
        assert.ok(lib.bundled.length > 0);
        assert.ok((await tool('read_genome', { id:lib.bundled[0], limit:1 })).stages.total > 0);
        for (const name of ['Leech','Earthworm','Big walker']) {
            const id='bundled/'+name;
            const {cells}=await tool('read_genome',{id,view:'cells',limit:1});
            const {links}=await tool('read_genome',{id,view:'links',limit:1});
            assert.ok(cells.total>1 && links.total>0);
            console.log(name,cells.total,'cells,',links.total,'physical links');
        }
        assert.ok(catalog.cells.some(c=>c.key==='speed_sensor'));
        assert.ok(catalog.formulas.some(f => f.key === 'lif'));
        assert.equal((await tool('request_body_scale')).status, 'ask_user');
        const cells = [
            {type:'leaf',x:1,y:1},
            {type:'leaf',x:1.6,y:1},
            {type:'neuron',x:1.6+.6*Math.cos(1),y:1+.6*Math.sin(1)}
        ];
        const shape = {width:4,height:4,cells,links:[[0,1],[1,2]]};
        for (const [angle, seesTarget] of [[Math.PI, true], [0, false]]) {
            const sight = await tool('create_body_layout', {
                width:4, height:4, links:[[0,1]], cells:[
                    {type:'eye', x:1, y:2, angle}, {type:'leaf', x:1.8, y:2}
                ]
            });
            const sightTrial = await tool('test_body_layout', {id:sight.id, ticks:60});
            assert.deepEqual(sightTrial.eyeProbes.map(p => [p.eyeId,p.seesExternalTarget,p.blockingCellId]),
                [[0,seesTarget,seesTarget ? null : 1]]);
        }
        for (const invalid of [
            {...shape, width:1}, {...shape,links:[[0,2],[1,2]]},
            {...shape,cells:Array(1025).fill(cells[0])},
            {...shape,cells:cells.map(c=>({...c,type:'fat'}))},
            {...shape,cells:[{...cells[0],radius:.4},...cells.slice(1)]}
        ]) assert.equal((await raw('create_body_layout',invalid)).isError,true);
        const fine=await tool('create_body_layout',{...shape,cells:[{...cells[0],radius:.4,fineDetailReason:'explicitly requested thin tip'},...cells.slice(1)]});
        const stored=await tool('read_body_layout',{id:fine.id,limit:1});
        assert.equal(stored.page.items[0].radius,.4);
        const {id:layoutId}=await tool('create_body_layout',shape);
        assert.equal((await raw('create_genome',{layoutId,name:'Test organism',stages:catalog.exampleStages})).isError,true);
        assert.equal((await raw('approve_body_layout',{id:layoutId})).isError,true);
        const viability=await tool('test_body_layout',{id:layoutId,ticks:240});
        assert.ok(viability.maxInitialPositionError < 0.0001);
        assert.equal(viability.survivedIntact,true,JSON.stringify(viability));
        assert.equal((await raw('approve_body_layout',{id:layoutId})).isError,true);
        const [debug]=await tool('preview_body_layout',{id:layoutId,view:'settled'});
        assert.equal(debug.mimeType,'image/png');
        const [large]=await tool('preview_body_layout',{id:layoutId,view:'settled',size:1024});
        assert.equal(Buffer.from(large.data,'base64').readUInt32BE(16),1024);
        assert.ok((await tool('approve_body_layout',{id:layoutId})).approved);
        catalog.exampleStages[0].cellActions[0].mutate = {cellType:'leaf'};
        const stages = [...catalog.exampleStages, {cellActions:{1:{divide:{id:2,angle:1,cellType:'neuron',funActivation:'lif',physicalLink:{1:{length:0.6}}}}}}];
        const draft = await tool('create_genome', {name:'Test organism', layoutId, stages});
        assert.equal((await raw('create_genome', {name:'bad', layoutId, stages:[{cellActions:{404:{divide:{id:1,angle:0,cellType:'leaf'}}}}]})).isError, true);
        const bent=structuredClone(stages);
        bent[1].cellActions[1].divide.angle=-1.5;
        const mismatch=await tool('create_genome',{name:'Bent test fixture',layoutId,stages:bent});
        const checked=await tool('validate_genome',{id:mismatch.id});
        assert.equal(checked.valid,false);
        assert.ok(checked.layoutComparison.maxPositionErrorWorldUnits>.1);
        assert.equal((await raw('save_genome',{id:mismatch.id})).isError,true);
        const validation = await tool('validate_genome', {id:draft.id});
        assert.equal(validation.layoutComparison.matchesApprovedLayout,true,JSON.stringify(validation.layoutComparison));
        const first = await tool('read_growth', {id:draft.id,tick:0});
        const last = await tool('read_growth', {id:draft.id,tick:first.lastTick});
        assert.ok(first.cells.total < last.cells.total);
        assert.equal(last.cells.total, 3);
        const parent = last.cells.items.find(cell => cell.id === 1);
        const child = last.cells.items.find(cell => cell.id === 2);
        assert.ok(Math.abs(Math.hypot(child.x - parent.x, child.y - parent.y) - 0.6) < 0.001);
        assert.equal(validation.cells, 3);
        assert.equal(validation.physicalLinks, 2);
        const geometry = await tool('read_genome', {id:draft.id,view:'cells'});
        assert.equal(geometry.cells.total, 3);
        const [image] = await tool('preview_genome', {id:draft.id});
        const bytes = Buffer.from(image.data,'base64');
        assert.equal(bytes.readUInt32BE(0), 0x89504e47);
        assert.equal(bytes.readUInt32BE(16),384);
        await writeFile(join(dir,'preview.png'),bytes);
        const saved = await tool('save_genome', {id:draft.id});
        assert.equal(saved.id, 'Test organism');
        assert.ok((await tool('list_genomes')).player.includes(saved.id));
        assert.deepEqual(await tool('read_genome',{id:saved.id,view:'cells'}), geometry);
        assert.equal((await tool('read_genome',{id:saved.id,limit:1})).stages.nextOffset,1);
        const genomeBytes = await readFile(join(dir, 'genomes', saved.fileName));
        assert.ok(genomeBytes.length > 0);
        const imported = join(dir, 'session', 'attachments', 'imported');
        const snapshots = (await readdir(imported)).filter(name => name.endsWith('-' + saved.fileName));
        assert.equal(snapshots.length, 1, 'The save command must have its replay attachment');
        assert.deepEqual(await readFile(join(imported, snapshots[0])), genomeBytes);
        assert.equal((await tool('save_genome', {id:draft.id})).id, saved.id);
        console.log(JSON.stringify({validation, saved, pngBytes:bytes.length, dir}));
    } else {
        if (route === 'GoSimulation') {
            const requestId=++seq;
            const send = message => server.stdin.write(JSON.stringify({jsonrpc:'2.0',...message})+'\n');
            send({id:requestId,method:'tools/call',params:{name:'request_body_scale',arguments:{}}});
            const form=JSON.parse((await messages.next()).value);
            assert.equal(form.method,'elicitation/create');
            await rpc('ping',{});
            send({id:form.id,result:{action:'accept',content:{width:4,height:5}}});
            const answer=JSON.parse((await messages.next()).value);
            assert.equal(answer.id,requestId);
            assert.deepEqual(answer.result.structuredContent,{status:'accepted',width:4,height:5});
            let body;
            for (let i=0;i<40 && !body;i++) {
                const {page} = await tool('read_state',{view:'organisms',limit:1});
                body = page.items[0];
                if (!body) await delay(150);
            }
            assert.ok(body, 'test click did not spawn an organism');
            const sample = await tool('measure_organism',{organism:body.slot,generation:body.generation});
            assert.ok(sample.cells > 0 && Number.isFinite(sample.centerX));
            for(let i=0;i<160 && !body.adult;i++) {
                const {page}=await tool('read_state',{view:'organisms',limit:4});
                body=page.items.find(o=>o.adult) ?? body;
                if (!body.adult) await delay(100);
            }
            assert.ok(body.adult,'motion trial requires an adult fixture: '+JSON.stringify(await tool('read_state',{view:'organisms',limit:4})));
            const [liveDebug]=await tool('preview_organism',{organism:body.slot,generation:body.generation});
            assert.equal(liveDebug.mimeType,'image/png');
            const trial=await tool('start_motion_trial',{organism:body.slot,generation:body.generation,forwardX:1,forwardY:0,distance:100,ticks:24,windowTicks:8,minForwardEfficiency:.85});
            let report=trial;
            for(let i=0;i<60 && report.status==='running';i++) {
                await delay(100);
                report=await tool('read_motion_trial',{id:trial.id});
            }
            assert.equal(report.status,'complete',JSON.stringify(report));
            assert.equal(report.windowProgress.length,3);
            assert.equal(report.minForwardEfficiency,.85);
            assert.ok(Math.abs(report.forwardEfficiency-(report.pathLength ? report.netForward/report.pathLength : 0))<1e-8);
            assert.ok(Math.abs(report.windowProgress.reduce((sum,v)=>sum+v,0)-report.netForward)<1e-8);
            assert.equal(report.sustainedForward,false);
            console.log(JSON.stringify(report));
        }
        for (const view of ['cells','organisms','links','neuralLinks']) {
            const state = await tool('read_state', {view,limit:1});
            assert.ok(state.page.items.length <= 1);
            console.log(view, state.source, state.tick ?? state.selectedEditorTick, state.page.total);
        }
    }
} finally {
    clearTimeout(timeout);
    server.stdin.end();
    game.kill();
    log.end();
}
