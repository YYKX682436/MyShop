"use client"

import type { ReactNode } from "react"
import { AnimatePresence, MotionConfig, motion } from "motion/react"

export const motionTokens = {
  micro: { duration: 0.14, ease: "easeOut" as const },
  popover: { duration: 0.14, ease: "easeOut" as const },
  dialog: { duration: 0.18, ease: "easeOut" as const },
  inspector: { type: "spring" as const, stiffness: 340, damping: 34, mass: 0.8 },
  workspace: { type: "spring" as const, stiffness: 300, damping: 32, mass: 0.9 },
  reveal: { duration: 0.28, ease: "easeOut" as const },
}

export function MotionProvider({ children }: { children: ReactNode }) {
  return (
    <MotionConfig reducedMotion="user" transition={motionTokens.micro}>
      {children}
    </MotionConfig>
  )
}

export function FadeIn({
  children,
  delay = 0,
  className,
}: {
  children: ReactNode
  delay?: number
  className?: string
}) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ ...motionTokens.reveal, delay }}
    >
      {children}
    </motion.div>
  )
}

export function Reveal({
  children,
  className,
  delay = 0,
}: {
  children: ReactNode
  className?: string
  delay?: number
}) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 6 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.12 }}
      transition={{ ...motionTokens.reveal, delay }}
    >
      {children}
    </motion.div>
  )
}

export function Stagger({
  children,
  className,
  id,
  delayChildren = 0,
  staggerChildren = 0.02,
}: {
  children: ReactNode
  className?: string
  id?: string
  delayChildren?: number
  staggerChildren?: number
}) {
  return (
    <motion.div
      className={className}
      id={id}
      initial="hidden"
      animate="show"
      variants={{
        hidden: {},
        show: { transition: { delayChildren, staggerChildren } },
      }}
    >
      {children}
    </motion.div>
  )
}

export function StaggerItem({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      className={className}
      variants={{ hidden: { opacity: 0, y: 4 }, show: { opacity: 1, y: 0 } }}
      transition={motionTokens.reveal}
    >
      {children}
    </motion.div>
  )
}

export { AnimatePresence as AnimatedPresence, motion }
