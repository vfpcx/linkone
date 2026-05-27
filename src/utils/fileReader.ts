import { promises as fs } from 'fs';
import { join } from 'path';

export async function readCLAUDEFile(): Promise<string> {
    const filePath = join(__dirname, '../../CLAUDE.md');
    try {
        const data = await fs.readFile(filePath, 'utf-8');
        return data;
    } catch (error) {
        throw new Error(`Error reading CLAUDE.md: ${error.message}`);
    }
}