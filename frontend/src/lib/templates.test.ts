import test from 'node:test'
import assert from 'node:assert/strict'
import {composePrompt, examplePrompt, readCreationOptions} from './templates.ts'

test('模板只替换一轮字面占位符，保留用户输入并正确处理选填项', () => {
    assert.equal(composePrompt('画{{subject}}，{{style}}。', {
        subject: '$& {{style}}',
        style: ' 水彩 '
    }), '画$& {{style}}，水彩。')
    assert.equal(composePrompt('{{subject}} / {{subject}} / {{optional}}', {subject: '猫'}), '猫 / 猫 / ')
    assert.equal(examplePrompt({
        promptPattern: '一只{{subject}}',
        fields: [{key: 'subject', label: '主体', example: '橘猫', required: true}]
    }), '一只橘猫')
    assert.deepEqual(readCreationOptions(null), {modelId: '', size: '', quality: ''})
    assert.equal(composePrompt('{{constructor}}', {}), '')
    assert.equal(examplePrompt({promptPattern: '画面保留字面 {{text}}', fields: []}), '画面保留字面 {{text}}')
})
