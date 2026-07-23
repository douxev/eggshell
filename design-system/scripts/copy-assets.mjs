/**
 * Assembles the shipped stylesheet and copies the self-hosted fonts.
 *
 * dist/styles.css is a single concatenated file (tokens + themes) so that a
 * consumer needs exactly one CSS import, and font URLs stay relative to it.
 */
import { readFile, writeFile, mkdir, copyFile, readdir } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const src = join(root, 'src');
const dist = join(root, 'dist');

await mkdir(join(dist, 'fonts'), { recursive: true });

const parts = [];
for (const name of ['tokens.css', 'themes.css']) {
  parts.push(await readFile(join(src, 'styles', name), 'utf8'));
}
await writeFile(join(dist, 'styles.css'), parts.join('\n'), 'utf8');

for (const f of await readdir(join(src, 'fonts'))) {
  await copyFile(join(src, 'fonts', f), join(dist, 'fonts', f));
}

console.log('[copy-assets] dist/styles.css + dist/fonts ready');
