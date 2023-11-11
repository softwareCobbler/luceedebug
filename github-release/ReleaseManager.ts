import * as fs from "node:fs"
import * as path from "node:path"
import * as process from "node:process"
import * as child_process from "node:child_process"
import { Octokit } from "@octokit/core"

async function makeRelease(authToken: string, tagName: string) {
    const octokit = new Octokit({
        auth: authToken
    })

    return await octokit.request('POST /repos/{owner}/{repo}/releases', {
        owner: 'softwareCobbler',
        repo: 'luceedebug',
        tag_name: tagName,
        name: tagName,
        body: `build for ${tagName}`,
        draft: false,
        prerelease: false,
        generate_release_notes: false,
        headers: {
            'X-GitHub-Api-Version': '2022-11-28'
        }
    })
}

async function addFileToRelease(authToken: string, releaseID: number, name: string, bytes: ArrayBuffer) {
    const result = await fetch(`https://uploads.github.com/repos/softwareCobbler/luceedebug/releases/${releaseID}/assets?name=${name}`, {
        method: "POST",
        headers: {
            Accept: `application/vnd.github+json`,
            Authorization: `Bearer ${authToken}`,
            "Content-Type": `application/octet-stream`
        },
        body: bytes
    })

    console.log(result.status)
    console.log(result.statusText)
    console.log(await result.json())
}

async function inspectReleases() {
    const octokit = new Octokit({
        auth: process.env[EnvVars.authToken]
    })
    
    // const releases = await octokit.request('GET /repos/{owner}/{repo}/releases', {
    //     owner: 'softwareCobbler',
    //     repo: 'luceedebug',
    //     headers: {
    //         'X-GitHub-Api-Version': '2022-11-28'
    //     }
    // });

    const releases = await octokit.request('GET /repos/{owner}/{repo}/releases/latest', {
        owner: 'softwareCobbler',
        repo: 'luceedebug',
        headers: {
          'X-GitHub-Api-Version': '2022-11-28'
        }
      })

    console.log(releases)
}

enum EnvVars {
    authToken = `LUCEEDEBUG_RELEASE_GITHUB_AUTH_TOKEN`,
    tag = `LUCEEDEBUG_RELEASE_TAG`
}

async function doit() {
    const authToken = process.env[EnvVars.authToken];
    const tag = process.env[EnvVars.tag]
    
    if (!authToken || !tag) {
        const required = []
        !authToken ? required.push(EnvVars.authToken) : 0;
        !tag ? required.push(EnvVars.tag) : 0;
        throw Error(`missing required env vars ${required.join(",")}`);
    }

    // sanity check that the target tag from env is the currently checked out version
    const tagCommit = child_process.execSync(`cd .. && git rev-list -n 1 ${tag}`).toString()
    const currentCommit = child_process.execSync(`cd .. && git rev-list -n 1 HEAD`).toString()

    if (tagCommit !== currentCommit) {
        throw Error("target commit based on supplied tag is not current HEAD?")
    }

    // run build 
    child_process.execSync("cd .. && gradlew shadowJar", {stdio: "inherit"})

    const bytes = fs.readFileSync(path.resolve("../luceedebug/build/libs/luceedebug.jar"))

    // make release and get associated ID, then add file
    const {data: {id}} = await makeRelease(authToken, tag)
    // const id = <<>>

    await addFileToRelease(authToken, id, "luceedebug.jar", bytes);
}

// await doit();
await inspectReleases();
