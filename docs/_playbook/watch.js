const express = require('express');
const serveIndex = require('serve-index');
const app = express()
const chokidar = require('chokidar');
const childProcess = require("node:child_process");
const path = require('node:path');
const fs = require('node:fs');

const SITE_DIR = path.join(__dirname, 'build', 'site');

const IGNORED_DIRS = new Set(['_playbook', '.git', '.cache']);

const createWatcher = (dir) => chokidar.watch(dir, {
    ignored: (p) => p.split(path.sep).some((s) => s.startsWith('.') || IGNORED_DIRS.has(s)),
    persistent: true,
})
        .on('change', rebuild)
        .on('unlink', rebuild)
        .on('error', rebuild);

let building = false
let triggeredDuringBuild = false
const rebuild = (changedPath) => {
    if (changedPath) {
        console.log(`File ${changedPath} has been changed, rebuilding site...`)
    }
    if (building) {
        console.log("Triggered during build, waiting for build to finish...")
        triggeredDuringBuild = true
        return
    }
    triggeredDuringBuild = false
    building = true;
    fs.rmSync(SITE_DIR, {recursive: true, force: true})
    const process = childProcess.spawn("npx", ["antora", "playbook.yaml"], {stdio: 'inherit'})
    process.on("exit", (code) => {
        if (code === 0) {
            console.log("Site rebuilt successfully!")
        } else {
            console.error("Failed to rebuild site!")
        }
        building = false
        if (triggeredDuringBuild) {
            rebuild('(changes during previous build)')
        }
    })
}

createWatcher(__dirname + "/..")

app.use(express.static('build/site'))
app.use(serveIndex('build/site', {
    icons: false,
    view: 'details'
}))

app.listen(3000, () => {
    console.log(`Started serving files on port 3000 (http://0.0.0.0:3000)!`)
})
rebuild()
