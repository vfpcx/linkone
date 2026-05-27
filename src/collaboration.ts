import { readFileSync } from 'fs';
import { join } from 'path';

export function readCLAUDEFile(): string {
    const filePath = join(__dirname, '..', '..', 'CLAUDE.md');
    try {
        const content = readFileSync(filePath, 'utf-8');
        return content;
    } catch (error) {
        console.error('Error reading CLAUDE.md:', error);
        return '';
    }
}