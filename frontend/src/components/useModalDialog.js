import { useEffect } from 'react'

export function useModalDialog(dialogRef, focusRef, returnFocusRef) {
  useEffect(() => {
    const dialog = dialogRef.current
    const previousFocus = returnFocusRef?.current || document.activeElement
    const originalOverflow = document.body.style.overflow
    const originalPadding = document.body.style.paddingRight
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth
    const bodyPadding = Number.parseFloat(getComputedStyle(document.body).paddingRight) || 0
    document.body.style.overflow = 'hidden'
    if (scrollbarWidth > 0) document.body.style.paddingRight = `${bodyPadding + scrollbarWidth}px`
    dialog.showModal()
    focusRef.current?.focus({ preventScroll: true })
    return () => {
      dialog.close()
      document.body.style.overflow = originalOverflow
      document.body.style.paddingRight = originalPadding
      if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
    }
  }, [dialogRef, focusRef, returnFocusRef])
}
