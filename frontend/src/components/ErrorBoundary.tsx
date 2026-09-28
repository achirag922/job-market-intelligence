import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  failed: boolean;
}

/**
 * V7.8: if a page throws while rendering, show a plain message with a way out instead of a
 * blank screen. The error itself goes to the browser console for developers; the user never
 * sees a stack trace.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Page failed to render', error, info.componentStack);
  }

  render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div className="card" role="alert">
        <h2>Something went wrong on this page</h2>
        <p className="card-description">
          The rest of the application still works. Try again, or go back to the dashboard.
        </p>
        <div className="row" style={{ gap: 8 }}>
          <button type="button" onClick={() => this.setState({ failed: false })}>
            Try again
          </button>
          <a className="button-link" href="/">
            Go to the dashboard
          </a>
        </div>
      </div>
    );
  }
}
