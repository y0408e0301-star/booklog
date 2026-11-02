import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import StarInput from './StarInput'
import Stars from './Stars'

// 星（画面設計書 2.8）
describe('Stars', () => {
  it('星 5 つと数字を並べ、読み上げは「星 4（5 段階中）」にする', () => {
    const { container } = render(<Stars value={4} size={16} />)
    expect(container).toHaveTextContent('星 4（5 段階中）★★★★☆4')
    // 星の形と数字は読み上げない
    expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(2)
  })

  it('平均評価は小数点以下 1 桁で出し、四捨五入した数だけ塗る', () => {
    const { container, rerender } = render(<Stars value={4.2} size={24} average />)
    expect(container).toHaveTextContent('星 4.2（5 段階中）★★★★☆4.2')
    rerender(<Stars value={4.5} size={24} average />)
    expect(container).toHaveTextContent('★★★★★4.5')
    rerender(<Stars value={1} size={24} average />)
    expect(container).toHaveTextContent('★☆☆☆☆1.0')
  })
})

function Input({ initial = null }: { initial?: number | null }) {
  const [value, setValue] = useState<number | null>(initial)
  return <StarInput value={value} onChange={setValue} />
}

describe('StarInput', () => {
  it('選んでいなければ「選んでいません」、押した星まで塗る', async () => {
    const { container } = render(<Input />)
    expect(screen.getByRole('group', { name: '星（必須）' })).toBeInTheDocument()
    expect(container).toHaveTextContent('☆☆☆☆☆選んでいません')

    await userEvent.click(screen.getByRole('radio', { name: '星 4' }))
    expect(screen.getByRole('radio', { name: '星 4' })).toBeChecked()
    expect(container).toHaveTextContent('★★★★☆4（5 段階中）')
  })

  it('左右の矢印キーで 1 つずつ変えられる', async () => {
    render(<Input initial={3} />)
    await userEvent.click(screen.getByRole('radio', { name: '星 3' }))
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('radio', { name: '星 4' })).toBeChecked()
    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(screen.getByRole('radio', { name: '星 2' })).toBeChecked()
  })

  it('メッセージがあれば星の下に出し、グループの説明にする', () => {
    render(<StarInput value={null} onChange={() => {}} error="星を 1〜5 から選んでください。" />)
    expect(screen.getByRole('group', { name: '星（必須）' })).toHaveAccessibleDescription('星を 1〜5 から選んでください。')
  })
})
