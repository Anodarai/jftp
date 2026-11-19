import { readFileSync } from 'node:fs';

const source = readFileSync(process.argv[2], 'utf8');
const target = /^([\t ]*)\/\*\*\r?\n[\t ]*\* @return Returns the licenseAgreedForVersion\.\r?\n[\t ]*\*\/\r?\n(?=[\t ]*public String getLicenseAgreedForVersion\(\) \{)/m;
process.stdout.write(source.replace(target, ''));
