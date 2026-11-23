'use client'

import * as React from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { cn } from '@/lib/utils'

interface DrawerProps extends React.ComponentPropsWithoutRef<typeof DialogPrimitive.Root> {
  direction?: 'bottom' | 'top' | 'left' | 'right'
  dismissible?: boolean
  fixed?: boolean
  repositionInputs?: boolean
  shouldScaleBackground?: boolean
}

interface DrawerContextType {
  open: boolean
}

const DrawerContext = React.createContext<DrawerContextType>({ open: false })

function Drawer({
  open,
  defaultOpen,
  onOpenChange,
  children,
  ...props
}: DrawerProps) {
  const [internalOpen, setInternalOpen] = React.useState(defaultOpen ?? false)
  const isControlled = open !== undefined
  const currentOpen = isControlled ? open : internalOpen

  const handleOpenChange = React.useCallback(
    (nextOpen: boolean) => {
      if (!isControlled) setInternalOpen(nextOpen)
      onOpenChange?.(nextOpen)
    },
    [isControlled, onOpenChange]
  )

  return (
    <DrawerContext.Provider value={{ open: currentOpen }}>
      <DialogPrimitive.Root
        open={currentOpen}
        onOpenChange={handleOpenChange}
        {...props}
      >
        {children}
      </DialogPrimitive.Root>
    </DrawerContext.Provider>
  )
}
Drawer.displayName = 'Drawer'

const DrawerTrigger = DialogPrimitive.Trigger
const DrawerPortal = DialogPrimitive.Portal
const DrawerClose = DialogPrimitive.Close

type DrawerOverlayProps = Omit<
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>,
  'onDrag' | 'onDragStart' | 'onDragEnd' | 'onAnimationStart'
>

const DrawerOverlay = React.forwardRef<HTMLDivElement, DrawerOverlayProps>(
  ({ className, ...props }, ref) => (
    <motion.div
      ref={ref}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.18, ease: 'easeOut' }}
      className={cn('fixed inset-0 z-50 bg-black/45 backdrop-blur-[2px]', className)}
      {...props}
    />
  )
)
DrawerOverlay.displayName = 'DrawerOverlay'

type DrawerContentProps = Omit<
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>,
  'onDrag' | 'onDragStart' | 'onDragEnd' | 'onAnimationStart'
>

const DrawerContent = React.forwardRef<HTMLDivElement, DrawerContentProps>(
  ({ className, children, ...props }, ref) => {
  const { open } = React.useContext(DrawerContext)
  const reducedMotion = useReducedMotion() ?? false

  return (
    <DialogPrimitive.Portal forceMount>
      <AnimatePresence>
        {open && (
          <>
            <DialogPrimitive.Overlay asChild forceMount>
              <DrawerOverlay />
            </DialogPrimitive.Overlay>
            <DialogPrimitive.Content asChild forceMount>
              <motion.div
                ref={ref}
                initial={
                  reducedMotion
                    ? { opacity: 0 }
                    : { opacity: 0, scale: 0.35, y: 35 }
                }
                animate={
                  reducedMotion
                    ? { opacity: 1 }
                    : { opacity: 1, scale: 1, y: 0 }
                }
                exit={
                  reducedMotion
                    ? { opacity: 0 }
                    : { opacity: 0, scale: 0.35, y: 35 }
                }
                style={{ transformOrigin: 'bottom right' }}
                transition={{
                  type: 'spring',
                  damping: 28,
                  stiffness: 360,
                  mass: 0.7,
                }}
                className={cn(
                  'static-material fixed inset-x-0 bottom-0 z-50 flex h-auto flex-col outline-none',
                  className
                )}
                {...props}
              >
                {children}
              </motion.div>
            </DialogPrimitive.Content>
          </>
        )}
      </AnimatePresence>
    </DialogPrimitive.Portal>
  )
})
DrawerContent.displayName = 'DrawerContent'

const DrawerHeader = ({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) => (
  <div
    className={cn('grid gap-1.5 p-4 text-center sm:text-left', className)}
    {...props}
  />
)
DrawerHeader.displayName = 'DrawerHeader'

const DrawerFooter = ({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) => (
  <div
    className={cn('mt-auto flex flex-col gap-2 p-4', className)}
    {...props}
  />
)
DrawerFooter.displayName = 'DrawerFooter'

const DrawerTitle = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Title>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Title>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Title
    ref={ref}
    className={cn(
      'text-lg font-semibold leading-none tracking-tight',
      className
    )}
    {...props}
  />
))
DrawerTitle.displayName = 'DrawerTitle'

const DrawerDescription = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Description>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Description>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Description
    ref={ref}
    className={cn('text-sm text-muted-foreground', className)}
    {...props}
  />
))
DrawerDescription.displayName = 'DrawerDescription'

export {
  Drawer,
  DrawerPortal,
  DrawerOverlay,
  DrawerTrigger,
  DrawerClose,
  DrawerContent,
  DrawerHeader,
  DrawerFooter,
  DrawerTitle,
  DrawerDescription,
}
