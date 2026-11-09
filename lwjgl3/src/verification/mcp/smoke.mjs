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
    const init = await rpc('initialize', { protocolVersion:'2025-11-25', capabilities:{}, clientInfo:{name:'smoke',version:'1'} });
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
        assert.ok(catalog.formulas.some(f => f.key === 'lif'));
        const stages = [...catalog.exampleStages, {cellActions:{1:{divide:{id:2,angle:1,cellType:'neuron',funActivation:'lif',physicalLink:{1:{length:0.6}}}}}}];
        const draft = await tool('create_genome', {name:'Test organism', stages});
        assert.equal((await raw('create_genome', {name:'bad', stages:[{cellActions:{404:{divide:{id:1,angle:0,cellType:'leaf'}}}}]})).isError, true);
        const validation = await tool('validate_genome', {id:draft.id});
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
            let body;
            for (let i=0;i<40 && !body;i++) {
                const {page} = await tool('read_state',{view:'organisms',limit:1});
                body = page.items[0];
                if (!body) await delay(150);
            }
            assert.ok(body, 'test click did not spawn an organism');
            const sample = await tool('measure_organism',{organism:body.slot,generation:body.generation});
            assert.ok(sample.cells > 0 && Number.isFinite(sample.centerX));
            console.log(JSON.stringify(sample));
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
