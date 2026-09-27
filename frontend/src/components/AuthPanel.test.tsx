import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AuthPanel } from './AuthPanel'

function respond(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AuthPanel', () => {
  it('registers, then signs in and hands the token to the app', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(respond(201, { id: 'merchant-1' }))
      .mockResolvedValueOnce(respond(200, { accessToken: 'token-1', tokenType: 'Bearer', expiresInSeconds: 900 }))
    vi.stubGlobal('fetch', fetchMock)
    const onSignedIn = vi.fn()
    const user = userEvent.setup()
    render(<AuthPanel onSignedIn={onSignedIn} />)

    await user.click(screen.getByRole('button', { name: 'Create an account' }))
    await user.type(screen.getByLabelText('Business name'), 'Demo Store')
    await user.type(screen.getByLabelText('Email'), 'merchant@example.com')
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    await vi.waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('token-1'))
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/v1/auth/register', '/api/v1/auth/login'])
  })

  it('shows the server message when sign-in fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(401, { detail: 'Email or password is incorrect' })))
    const user = userEvent.setup()
    render(<AuthPanel onSignedIn={vi.fn()} />)

    await user.type(screen.getByLabelText('Email'), 'merchant@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect')
  })
})
