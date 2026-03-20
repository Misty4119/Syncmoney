

export class AppError extends Error {
  constructor(
    message: string,
    public code: string,
    public status?: number
  ) {
    super(message)
    this.name = 'AppError'
  }
}

export function handleError(error: unknown): AppError {
  if (error instanceof Error) {
    return new AppError(error.message, 'UNKNOWN_ERROR')
  }
  return new AppError(String(error), 'UNKNOWN_ERROR')
}
