import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("account home owns the complete logged-in summary and scoped account actions", () => {
  const source = readSource("../app/(store)/my/page.tsx")
  const avatarSource = readSource("../components/store/account-avatar.tsx")

  for (const route of ["/my/orders", "/my/security", "/my/settings"]) {
    assert.equal(source.includes(`href="${route}"`), true, route)
  }
  assert.match(source, /user\.avatar_url/)
  assert.match(source, /user\.user_no/)
  assert.match(source, /user\.balance/)
  assert.match(source, /user\.user_level_name/)
  assert.match(source, /userApi\.uploadAvatar/)
  assert.match(source, /setUser\(updatedProfile\)/)
  assert.doesNotMatch(source, /\/profile/)
  assert.doesNotMatch(source, /my\.darkMode|my\.english/)
  assert.match(avatarSource, /<button/)
  assert.match(avatarSource, /onLoadingStatusChange/)
  assert.match(avatarSource, /getAvatarDisplayState/)
  for (const removedToken of [
    ["Camera"],
    ["Rotate", "Ccw"],
    ["<", "Button"],
    ["on", "Delete"],
    ["change", "Avatar"],
    ["restore", "Avatar"],
  ]) {
    assert.doesNotMatch(avatarSource, new RegExp(removedToken.join("")))
  }
  for (const removedToken of [
    ["handleAvatar", "Delete"],
    ["delete", "Avatar"],
    ["avatar", "Restored"],
  ]) {
    assert.doesNotMatch(source, new RegExp(removedToken.join("")))
  }
})

test("security flow clears in-memory auth state and navigates to login after password change", () => {
  const source = readSource("../components/store/change-password-form.tsx")

  assert.match(source, /userApi\.updatePassword/)
  assert.match(source, /setUser\(null\)/)
  assert.match(source, /router\.replace\("\/login"\)/)
  assert.match(source, /security\.passwordChanged/)
})

test("settings flow delegates language and all three theme modes to existing contexts", () => {
  const source = readSource("../app/(store)/my/settings/page.tsx")

  assert.match(source, /useLocale/)
  assert.match(source, /setLocale/)
  assert.match(source, /useTheme/)
  assert.match(source, /setTheme/)
  assert.match(source, /value: "system"/)
  assert.match(source, /settings\.chinese/)
  assert.match(source, /settings\.english/)
})

test("the removed profile page has no storefront route or page implementation", () => {
  assert.equal(readSource("./storefront-routes.ts").includes("/profile"), false)
  assert.throws(() => readFileSync(new URL("../app/(store)/profile/page.tsx", import.meta.url)), /ENOENT/)
})

test("avatar API surface keeps upload and removes default-avatar deletion", () => {
  const source = readSource("../services/api.ts")

  assert.match(source, /uploadAvatar/)
  assert.doesNotMatch(source, new RegExp(["delete", "Avatar"].join("")))
})
